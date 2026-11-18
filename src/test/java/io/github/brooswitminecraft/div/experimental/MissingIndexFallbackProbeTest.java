package io.github.brooswitminecraft.div.experimental;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MINECRAFT-545/547 (John's comments 54849/54856/54862/54870): pure coverage of
 * {@link MissingIndexFallbackProbe#classifyLeakVerdict} and
 * {@link MissingIndexFallbackProbe#runAllIndependently} - both small, Minecraft/MTS-free
 * policies the real fixture routes its own observed outcomes and its own cleanup through.
 * Referencing the enclosing class here does not construct any MTS object (confirmed elsewhere in
 * this investigation - the class's own static fields are null-initialized vehicle REFERENCES,
 * never touched until {@code maybeRun} actually executes, which no test here calls), the same
 * pattern already relied on by {@code PhysicsBridgeClassificationTest}'s own use of
 * {@code EntityVehicleF_Physics} as a bare type parameter.
 */
class MissingIndexFallbackProbeTest {
 private static final MissingIndexFallbackProbe.ReconnectOutcome NEW_CONNECTION =
   MissingIndexFallbackProbe.ReconnectOutcome.NEW_CONNECTION_OBSERVED;
 private static final MissingIndexFallbackProbe.ReconnectOutcome NO_NEW_CONNECTION =
   MissingIndexFallbackProbe.ReconnectOutcome.NO_NEW_CONNECTION_REQUEST_CONSUMED;
 private static final MissingIndexFallbackProbe.ReconnectOutcome NOT_CONSUMED =
   MissingIndexFallbackProbe.ReconnectOutcome.REQUEST_NOT_CONSUMED;
 private static final MissingIndexFallbackProbe.AnchorEligibility ELIGIBLE =
   MissingIndexFallbackProbe.AnchorEligibility.ELIGIBLE;
 private static final MissingIndexFallbackProbe.AnchorEligibility INELIGIBLE =
   MissingIndexFallbackProbe.AnchorEligibility.INELIGIBLE;

 private static MissingIndexFallbackProbe.ReconnectAttempt attempt(
     MissingIndexFallbackProbe.ReconnectOutcome outcome, MissingIndexFallbackProbe.AnchorEligibility eligibility) {
  return new MissingIndexFallbackProbe.ReconnectAttempt(outcome, eligibility);
 }

 private static MissingIndexFallbackProbe.LeakVerdict verdict(
     MissingIndexFallbackProbe.ReconnectOutcome controlOutcome, MissingIndexFallbackProbe.AnchorEligibility controlEligibility,
     MissingIndexFallbackProbe.ReconnectOutcome leakOutcome, MissingIndexFallbackProbe.AnchorEligibility leakEligibility) {
  return MissingIndexFallbackProbe.classifyLeakVerdict(attempt(controlOutcome, controlEligibility), attempt(leakOutcome, leakEligibility));
 }

 // --- classifyLeakVerdict --- (every case below uses ELIGIBLE on both sides unless the case is
 // specifically testing eligibility itself, isolating each check from the other)

 @Test void controlFailureIsAlwaysInconclusiveRegardlessOfLeakProbeResult() {
  // 54849/54856: "Failed control => INCONCLUSIVE, no cause claim." - checked for BOTH possible
  // leak-probe outcomes, since a failed (but genuinely consumed) control must override either.
  assertEquals(MissingIndexFallbackProbe.LeakVerdict.INCONCLUSIVE_CONTROL_FAILED,
    verdict(NO_NEW_CONNECTION, ELIGIBLE, NEW_CONNECTION, ELIGIBLE));
  assertEquals(MissingIndexFallbackProbe.LeakVerdict.INCONCLUSIVE_CONTROL_FAILED,
    verdict(NO_NEW_CONNECTION, ELIGIBLE, NO_NEW_CONNECTION, ELIGIBLE));
 }

 @Test void controlSuccessWithNoNewConnectionConfirmsTheLeak() {
  assertEquals(MissingIndexFallbackProbe.LeakVerdict.LEAK_CONFIRMED,
    verdict(NEW_CONNECTION, ELIGIBLE, NO_NEW_CONNECTION, ELIGIBLE));
 }

 @Test void controlSuccessWithNewConnectionMeansLeakNotObserved() {
  assertEquals(MissingIndexFallbackProbe.LeakVerdict.LEAK_NOT_OBSERVED,
    verdict(NEW_CONNECTION, ELIGIBLE, NEW_CONNECTION, ELIGIBLE));
 }

 // MINECRAFT-545/547 (John's comment 54870): REQUEST_NOT_CONSUMED is its own distinct outcome,
 // never folded into "no new connection" as if it were a meaningful rejection signal - it must
 // escalate to its own INCONCLUSIVE verdict, taking priority even over a control failure.

 @Test void controlRequestNotConsumedIsInconclusiveRegardlessOfLeakProbeResult() {
  assertEquals(MissingIndexFallbackProbe.LeakVerdict.INCONCLUSIVE_REQUEST_NOT_CONSUMED,
    verdict(NOT_CONSUMED, ELIGIBLE, NEW_CONNECTION, ELIGIBLE));
  assertEquals(MissingIndexFallbackProbe.LeakVerdict.INCONCLUSIVE_REQUEST_NOT_CONSUMED,
    verdict(NOT_CONSUMED, ELIGIBLE, NO_NEW_CONNECTION, ELIGIBLE));
 }

 @Test void leakRequestNotConsumedIsInconclusiveEvenWithASuccessfulControl() {
  // The control succeeded (genuinely consumed, new connection formed) - but if the LEAK probe's
  // own request was never consumed, the verdict must still be INCONCLUSIVE_REQUEST_NOT_CONSUMED,
  // never LEAK_CONFIRMED or LEAK_NOT_OBSERVED, since the leak probe's own result is meaningless.
  assertEquals(MissingIndexFallbackProbe.LeakVerdict.INCONCLUSIVE_REQUEST_NOT_CONSUMED,
    verdict(NEW_CONNECTION, ELIGIBLE, NOT_CONSUMED, ELIGIBLE));
 }

 @Test void requestNotConsumedTakesPriorityOverControlFailure() {
  // Both anomalies present at once - REQUEST_NOT_CONSUMED must still win, per the policy's own
  // documented check ordering (checked first, before the control-failure check).
  assertEquals(MissingIndexFallbackProbe.LeakVerdict.INCONCLUSIVE_REQUEST_NOT_CONSUMED,
    verdict(NOT_CONSUMED, ELIGIBLE, NOT_CONSUMED, ELIGIBLE));
 }

 // MINECRAFT-545/547 (John's comment 54929): classifyLeakVerdict previously had NO eligibility
 // input at all - the anchor distance was logged only, never actually fed into the verdict. An
 // ineligible anchor on EITHER side must now escalate to its own INCONCLUSIVE verdict, taking
 // priority over the control-failure check but never over REQUEST_NOT_CONSUMED (checked first).

 @Test void controlIneligibleAnchorIsInconclusiveRegardlessOfLeakProbeResult() {
  assertEquals(MissingIndexFallbackProbe.LeakVerdict.INCONCLUSIVE_INELIGIBLE_ANCHOR,
    verdict(NEW_CONNECTION, INELIGIBLE, NEW_CONNECTION, ELIGIBLE));
  assertEquals(MissingIndexFallbackProbe.LeakVerdict.INCONCLUSIVE_INELIGIBLE_ANCHOR,
    verdict(NEW_CONNECTION, INELIGIBLE, NO_NEW_CONNECTION, ELIGIBLE));
 }

 @Test void leakIneligibleAnchorIsInconclusiveEvenWithAnEligibleSuccessfulControl() {
  assertEquals(MissingIndexFallbackProbe.LeakVerdict.INCONCLUSIVE_INELIGIBLE_ANCHOR,
    verdict(NEW_CONNECTION, ELIGIBLE, NEW_CONNECTION, INELIGIBLE));
  assertEquals(MissingIndexFallbackProbe.LeakVerdict.INCONCLUSIVE_INELIGIBLE_ANCHOR,
    verdict(NEW_CONNECTION, ELIGIBLE, NO_NEW_CONNECTION, INELIGIBLE));
 }

 @Test void ineligibleAnchorTakesPriorityOverControlFailure() {
  // A failed control AND an ineligible anchor at once - ineligibility must still win, per the
  // policy's own documented check ordering (checked before the control-failure check).
  assertEquals(MissingIndexFallbackProbe.LeakVerdict.INCONCLUSIVE_INELIGIBLE_ANCHOR,
    verdict(NO_NEW_CONNECTION, INELIGIBLE, NEW_CONNECTION, ELIGIBLE));
 }

 @Test void requestNotConsumedTakesPriorityOverIneligibleAnchor() {
  // Both anomalies present at once - REQUEST_NOT_CONSUMED must still win over ineligibility too,
  // since it is checked FIRST, ahead of every other check including eligibility.
  assertEquals(MissingIndexFallbackProbe.LeakVerdict.INCONCLUSIVE_REQUEST_NOT_CONSUMED,
    verdict(NOT_CONSUMED, INELIGIBLE, NEW_CONNECTION, ELIGIBLE));
 }

 // --- classifyAnchorEligibility ---

 @Test void anchorDistanceAtOrBelowThresholdIsEligible() {
  assertEquals(ELIGIBLE, MissingIndexFallbackProbe.classifyAnchorEligibility(0.0));
  assertEquals(ELIGIBLE, MissingIndexFallbackProbe.classifyAnchorEligibility(3.9999));
  assertEquals(ELIGIBLE, MissingIndexFallbackProbe.classifyAnchorEligibility(4.0), "exactly at the connection's own distance threshold must still be eligible");
 }

 @Test void anchorDistanceAboveThresholdIsIneligible() {
  assertEquals(INELIGIBLE, MissingIndexFallbackProbe.classifyAnchorEligibility(4.0001));
  assertEquals(INELIGIBLE, MissingIndexFallbackProbe.classifyAnchorEligibility(100.0));
 }

 @Test void nonFiniteAnchorDistanceIsIneligible() {
  assertEquals(INELIGIBLE, MissingIndexFallbackProbe.classifyAnchorEligibility(Double.NaN));
  assertEquals(INELIGIBLE, MissingIndexFallbackProbe.classifyAnchorEligibility(Double.POSITIVE_INFINITY));
  assertEquals(INELIGIBLE, MissingIndexFallbackProbe.classifyAnchorEligibility(Double.NEGATIVE_INFINITY));
 }

 // --- runAllIndependently ---
 // MINECRAFT-545/547 (John's comment 54870): "Pure callback-based production-used orchestration
 // tests cover partial acquisition, first/middle remove failure, chunk failure, subsequent
 // attempts and no premature success - Minecraft construction is NOT needed to test sequencing."

 @Test void allActionsSucceedingReportsAllSucceeded() {
  var attempted = new ArrayList<String>();
  var actions = List.of(
    new MissingIndexFallbackProbe.CleanupAction("a", () -> attempted.add("a")),
    new MissingIndexFallbackProbe.CleanupAction("b", () -> attempted.add("b")),
    new MissingIndexFallbackProbe.CleanupAction("c", () -> attempted.add("c")));
  var failures = new ArrayList<String>();
  var outcome = MissingIndexFallbackProbe.runAllIndependently(actions, (tag, ex) -> failures.add(tag));
  assertEquals(MissingIndexFallbackProbe.CleanupOutcome.ALL_SUCCEEDED, outcome);
  assertEquals(List.of("a", "b", "c"), attempted);
  assertTrue(failures.isEmpty());
 }

 @Test void firstActionThrowingDoesNotPreventTheRestFromBeingAttempted() {
  // The exact "first/middle remove failure... subsequent attempts" requirement - a failure in
  // action "a" (the first) must not prevent "b" and "c" from being attempted.
  var attempted = new ArrayList<String>();
  var actions = List.of(
    new MissingIndexFallbackProbe.CleanupAction("a", () -> { throw new RuntimeException("a failed"); }),
    new MissingIndexFallbackProbe.CleanupAction("b", () -> attempted.add("b")),
    new MissingIndexFallbackProbe.CleanupAction("c", () -> attempted.add("c")));
  var failures = new ArrayList<String>();
  var outcome = MissingIndexFallbackProbe.runAllIndependently(actions, (tag, ex) -> failures.add(tag));
  assertEquals(MissingIndexFallbackProbe.CleanupOutcome.SOME_FAILED, outcome, "no premature success - a single failure must report SOME_FAILED");
  assertEquals(List.of("b", "c"), attempted, "actions after the failing one must still be attempted");
  assertEquals(List.of("a"), failures);
 }

 @Test void middleActionThrowingStillAttemptsTheLastOne() {
  var attempted = new ArrayList<String>();
  var actions = List.of(
    new MissingIndexFallbackProbe.CleanupAction("a", () -> attempted.add("a")),
    new MissingIndexFallbackProbe.CleanupAction("b", () -> { throw new RuntimeException("b failed"); }),
    new MissingIndexFallbackProbe.CleanupAction("c", () -> attempted.add("c")));
  var failures = new ArrayList<String>();
  var outcome = MissingIndexFallbackProbe.runAllIndependently(actions, (tag, ex) -> failures.add(tag));
  assertEquals(MissingIndexFallbackProbe.CleanupOutcome.SOME_FAILED, outcome);
  assertEquals(List.of("a", "c"), attempted, "both the action before AND after the failing one must still run");
  assertEquals(List.of("b"), failures);
 }

 @Test void everyActionThrowingStillAttemptsAllOfThemAndReportsEachFailure() {
  var actions = List.of(
    new MissingIndexFallbackProbe.CleanupAction("x", () -> { throw new RuntimeException("x"); }),
    new MissingIndexFallbackProbe.CleanupAction("y", () -> { throw new RuntimeException("y"); }));
  var failures = new ArrayList<String>();
  var outcome = MissingIndexFallbackProbe.runAllIndependently(actions, (tag, ex) -> failures.add(tag));
  assertEquals(MissingIndexFallbackProbe.CleanupOutcome.SOME_FAILED, outcome);
  assertEquals(List.of("x", "y"), failures, "every individual failure must be reported, not just the first");
 }

 @Test void emptyActionListReportsAllSucceededVacuously() {
  var outcome = MissingIndexFallbackProbe.runAllIndependently(List.of(), (tag, ex) -> fail("no action should fail with nothing to attempt"));
  assertEquals(MissingIndexFallbackProbe.CleanupOutcome.ALL_SUCCEEDED, outcome);
 }
}
