package io.github.brooswitminecraft.div.experimental;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MINECRAFT-545/547 (John's comments 54849/54856): pure coverage of
 * {@link MissingIndexFallbackProbe#classifyLeakVerdict} - the small, Minecraft/MTS-free verdict
 * policy the real fixture routes its own observed outcomes through. Referencing the enclosing
 * class here does not construct any MTS object (confirmed elsewhere in this investigation - the
 * class's own static fields are null-initialized vehicle REFERENCES, never touched until
 * {@code maybeRun} actually executes, which no test here calls), the same pattern already
 * relied on by {@code PhysicsBridgeClassificationTest}'s own use of {@code EntityVehicleF_Physics}
 * as a bare type parameter.
 */
class MissingIndexFallbackProbeTest {
 @Test void controlFailureIsAlwaysInconclusiveRegardlessOfLeakProbeResult() {
  // 54849/54856: "Failed control => INCONCLUSIVE, no cause claim." - checked for BOTH possible
  // leak-probe outcomes, since a failed control must override either one.
  assertEquals(MissingIndexFallbackProbe.LeakVerdict.INCONCLUSIVE_CONTROL_FAILED,
    MissingIndexFallbackProbe.classifyLeakVerdict(
      MissingIndexFallbackProbe.ControlOutcome.FAILURE,
      MissingIndexFallbackProbe.LeakProbeOutcome.NEW_CONNECTION_OBSERVED));
  assertEquals(MissingIndexFallbackProbe.LeakVerdict.INCONCLUSIVE_CONTROL_FAILED,
    MissingIndexFallbackProbe.classifyLeakVerdict(
      MissingIndexFallbackProbe.ControlOutcome.FAILURE,
      MissingIndexFallbackProbe.LeakProbeOutcome.NO_NEW_CONNECTION));
 }

 @Test void controlSuccessWithNoNewConnectionConfirmsTheLeak() {
  assertEquals(MissingIndexFallbackProbe.LeakVerdict.LEAK_CONFIRMED,
    MissingIndexFallbackProbe.classifyLeakVerdict(
      MissingIndexFallbackProbe.ControlOutcome.SUCCESS,
      MissingIndexFallbackProbe.LeakProbeOutcome.NO_NEW_CONNECTION));
 }

 @Test void controlSuccessWithNewConnectionMeansLeakNotObserved() {
  assertEquals(MissingIndexFallbackProbe.LeakVerdict.LEAK_NOT_OBSERVED,
    MissingIndexFallbackProbe.classifyLeakVerdict(
      MissingIndexFallbackProbe.ControlOutcome.SUCCESS,
      MissingIndexFallbackProbe.LeakProbeOutcome.NEW_CONNECTION_OBSERVED));
 }
}
