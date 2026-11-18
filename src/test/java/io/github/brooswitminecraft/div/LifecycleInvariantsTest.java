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

 // registryBodyIdentity is now the actual body REFERENCE (54635: System.identityHashCode is not
 // guaranteed collision-free, so an int stand-in was wrong) - these two distinct plain Objects
 // stand in for "two distinct live body instances"; null stands in for "no body registered."
 // SAME_BODY is the default for every test below that is not specifically about a body swap, so
 // the registryBodyIdentity check never fires where it isn't the point of the test.
 private static final Object SAME_BODY = new Object(), DIFFERENT_BODY = new Object(), NO_BODY = null;
 private static Sample s(UUID body, long gameTime, long count) { return new Sample(body, gameTime, count, SAME_BODY); }
 private static Sample s(UUID body, long gameTime, long count, Object registryBodyIdentity) { return new Sample(body, gameTime, count, registryBodyIdentity); }

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
  // registered at all (registryBodyIdentity == null, per PhysicsBridge.debugRegistryBodyIdentity's
  // own documented "null = absent" contract) - a real removal (a PRESENT-to-ABSENT transition),
  // correctly distinguished from a starved-but-still-live body AND from BODY_ABSENT below (which
  // is specifically "absent on BOTH sides," not a transition).
  assertEquals(Outcome.BODY_IDENTITY_CHANGED, LifecycleInvariants.evaluate(List.of(
    s(BODY_A, 100, 10, SAME_BODY), s(BODY_A, 101, 0, NO_BODY))));
 }

 @Test void evaluateBodyAbsentWhenNoBodyIsRegisteredOnEitherSide() {
  // MINECRAFT-545/547 (John's comment 54635): two consecutive samples with NO body registered on
  // EITHER side (registryBodyIdentity null both times) must NOT be read as FAIL_STARVED_TICK -
  // debugStepInvocationCount's own accessor returns 0 when absent, so both samples' counts are
  // 0, a zero delta, which is exactly the starvation pattern's own signature for a body that
  // genuinely exists but isn't stepping. There is no body at all here, so there is nothing to
  // have starved - BODY_ABSENT is the correct, distinct "nothing to check" outcome.
  assertEquals(Outcome.BODY_ABSENT, LifecycleInvariants.evaluate(List.of(
    s(BODY_A, 100, 0, NO_BODY), s(BODY_A, 101, 0, NO_BODY))));
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

 // MINECRAFT-545/547 (John's comment 54823): the pure required-phase classification/aggregation
 // seam BuoyantHitchProbe now routes every required-phase check through. No ServerLevel/MTS
 // dependency at all - exactly the "small pure aggregator/formatter seam with existing JUnit, no
 // native run/dependency needed" 54823 asked for, covering PASS, both FAIL outcomes, every
 // INCONCLUSIVE outcome, and mixed-phase aggregation.

 @Test void classifyRequiredPhaseOutcomeMapsPassToPass() {
  assertEquals(LifecycleInvariants.RequiredPhaseVerdict.PASS,
    LifecycleInvariants.classifyRequiredPhaseOutcome(Outcome.PASS));
 }

 @Test void classifyRequiredPhaseOutcomeMapsBothFailOutcomesToFail() {
  assertEquals(LifecycleInvariants.RequiredPhaseVerdict.FAIL,
    LifecycleInvariants.classifyRequiredPhaseOutcome(Outcome.FAIL_STARVED_TICK));
  assertEquals(LifecycleInvariants.RequiredPhaseVerdict.FAIL,
    LifecycleInvariants.classifyRequiredPhaseOutcome(Outcome.FAIL_DUPLICATE_TICK));
 }

 @Test void classifyRequiredPhaseOutcomeMapsEveryIncompleteOutcomeToInconclusive() {
  assertEquals(LifecycleInvariants.RequiredPhaseVerdict.INCONCLUSIVE,
    LifecycleInvariants.classifyRequiredPhaseOutcome(Outcome.INSUFFICIENT_SAMPLES));
  assertEquals(LifecycleInvariants.RequiredPhaseVerdict.INCONCLUSIVE,
    LifecycleInvariants.classifyRequiredPhaseOutcome(Outcome.BODY_IDENTITY_CHANGED));
  assertEquals(LifecycleInvariants.RequiredPhaseVerdict.INCONCLUSIVE,
    LifecycleInvariants.classifyRequiredPhaseOutcome(Outcome.BODY_ABSENT));
 }

 // MINECRAFT-545/547 (John's comment 54832): aggregateRequiredPhaseVerdicts is now
 // phase-identity-aware - keyed by Map<phaseName, verdict> plus the actual Set<String> of
 // required phase names, specifically so a phase checked twice can never substitute for a
 // DIFFERENT phase that was never checked at all (both produced the same bare-List shape under
 // the prior signature). Covers: all-pass, vacuous-pass-with-no-REQUIRED-phases (a different,
 // degenerate case from "phases are required but missing"), a required phase present but
 // INCONCLUSIVE, FAIL winning over INCONCLUSIVE, a required phase MISSING entirely (not merely
 // list-empty - this is the exact named gap), and a duplicated phase name merged via
 // worseOfRequiredPhaseVerdicts not masking the other, genuinely-missing phase.

 private static final LifecycleInvariants.RequiredPhaseVerdict PASS_V = LifecycleInvariants.RequiredPhaseVerdict.PASS;
 private static final LifecycleInvariants.RequiredPhaseVerdict FAIL_V = LifecycleInvariants.RequiredPhaseVerdict.FAIL;
 private static final LifecycleInvariants.RequiredPhaseVerdict INCONCLUSIVE_V = LifecycleInvariants.RequiredPhaseVerdict.INCONCLUSIVE;

 @Test void aggregateRequiredPhaseVerdictsPassWhenEveryRequiredPhasePasses() {
  var required = java.util.Set.of("A", "B");
  var byPhase = java.util.Map.of("A", PASS_V, "B", PASS_V);
  assertEquals(PASS_V, LifecycleInvariants.aggregateRequiredPhaseVerdicts(required, byPhase));
 }

 @Test void aggregateRequiredPhaseVerdictsVacuouslyPassWhenNoPhaseIsRequiredAtAll() {
  // A different, degenerate case from "phases are required but missing" - here nothing is
  // required in the first place, so there is nothing to have been left incomplete.
  assertEquals(PASS_V, LifecycleInvariants.aggregateRequiredPhaseVerdicts(java.util.Set.of(), java.util.Map.of()));
 }

 @Test void aggregateRequiredPhaseVerdictsInconclusiveWhenOneRequiredPhaseIsInconclusiveAndRestPass() {
  var required = java.util.Set.of("A", "B", "C");
  var byPhase = java.util.Map.of("A", PASS_V, "B", INCONCLUSIVE_V, "C", PASS_V);
  assertEquals(INCONCLUSIVE_V, LifecycleInvariants.aggregateRequiredPhaseVerdicts(required, byPhase));
 }

 @Test void aggregateRequiredPhaseVerdictsFailWhenAnyRequiredPhaseFailsRegardlessOfOthers() {
  var required = java.util.Set.of("A", "B", "C");
  var byPhase = java.util.Map.of("A", INCONCLUSIVE_V, "B", FAIL_V, "C", PASS_V);
  assertEquals(FAIL_V, LifecycleInvariants.aggregateRequiredPhaseVerdicts(required, byPhase));
 }

 @Test void aggregateRequiredPhaseVerdictsInconclusiveWhenARequiredPhaseWasNeverCheckedAtAll() {
  // The exact named gap (54832): "B" is required but has NO entry in byPhase at all - not
  // merely absent-with-a-null-verdict, genuinely never recorded. Must be INCONCLUSIVE, never a
  // silent PASS by omission, even though the one phase that WAS checked passed.
  var required = java.util.Set.of("A", "B");
  var byPhase = java.util.Map.of("A", PASS_V);
  assertEquals(INCONCLUSIVE_V, LifecycleInvariants.aggregateRequiredPhaseVerdicts(required, byPhase));
 }

 @Test void aggregateRequiredPhaseVerdictsMissingPhaseIsNotMaskedByADuplicateCheckOnAnotherPhase() {
  // The exact failure mode a bare List could not distinguish: "A" checked twice (merged via
  // worseOfRequiredPhaseVerdicts into one PASS slot) must NOT make this look like "every
  // required phase was checked" when "B" was never recorded at all.
  var required = java.util.Set.of("A", "B");
  var byPhase = new java.util.HashMap<String, LifecycleInvariants.RequiredPhaseVerdict>();
  byPhase.merge("A", PASS_V, LifecycleInvariants::worseOfRequiredPhaseVerdicts);
  byPhase.merge("A", PASS_V, LifecycleInvariants::worseOfRequiredPhaseVerdicts); // "A" checked twice; "B" never checked
  assertEquals(1, byPhase.size(), "merging two checks on the SAME phase name must occupy only one map slot");
  assertEquals(INCONCLUSIVE_V, LifecycleInvariants.aggregateRequiredPhaseVerdicts(required, byPhase));
 }

 @Test void worseOfRequiredPhaseVerdictsFailBeatsEverything() {
  assertEquals(FAIL_V, LifecycleInvariants.worseOfRequiredPhaseVerdicts(FAIL_V, PASS_V));
  assertEquals(FAIL_V, LifecycleInvariants.worseOfRequiredPhaseVerdicts(INCONCLUSIVE_V, FAIL_V));
 }

 @Test void worseOfRequiredPhaseVerdictsInconclusiveBeatsPass() {
  assertEquals(INCONCLUSIVE_V, LifecycleInvariants.worseOfRequiredPhaseVerdicts(PASS_V, INCONCLUSIVE_V));
 }

 @Test void worseOfRequiredPhaseVerdictsPassWhenBothPass() {
  assertEquals(PASS_V, LifecycleInvariants.worseOfRequiredPhaseVerdicts(PASS_V, PASS_V));
 }

 // MINECRAFT-545/547 (John's comment 54832): the terminal-banner formatter must handle all
 // three RequiredPhaseVerdict values explicitly and be independently testable, even though a
 // FAIL aggregate is normally unreachable at BuoyantHitchProbe's own one call site today (any
 // per-phase FAIL already returns earlier). Pure - no Minecraft/MTS dependency.

 @Test void formatRequiredPhaseAggregateBannerPassUsesTheSuppliedDetailText() {
  var text = LifecycleInvariants.formatRequiredPhaseAggregateBanner(PASS_V, "X/Y/Z all passed");
  assertEquals("COMPLETE - X/Y/Z all passed", text);
 }

 @Test void formatRequiredPhaseAggregateBannerInconclusiveDoesNotReuseThePassBannerText() {
  // The INCONCLUSIVE text legitimately mentions "all passed" once, as a quoted CONTRAST ("...
  // NOT the same as \"all passed\"...") - checked precisely for that, not banned as a bare
  // substring, which would also (wrongly) flag that deliberate contrast phrase.
  var text = LifecycleInvariants.formatRequiredPhaseAggregateBanner(INCONCLUSIVE_V, "X/Y/Z all passed");
  assertTrue(text.startsWith("INCONCLUSIVE"));
  assertFalse(text.contains("X/Y/Z all passed"), "an INCONCLUSIVE banner must never reuse the caller's own PASS-specific detail text");
 }

 @Test void formatRequiredPhaseAggregateBannerFailDoesNotReuseThePassBannerText() {
  // Exercised directly even though FAIL is normally unreachable at the one real call site -
  // the formatter itself must still map it correctly, defensively, not merely by omission.
  var text = LifecycleInvariants.formatRequiredPhaseAggregateBanner(FAIL_V, "X/Y/Z all passed");
  assertTrue(text.startsWith("FAILED"));
  assertFalse(text.contains("X/Y/Z all passed"));
 }
}
