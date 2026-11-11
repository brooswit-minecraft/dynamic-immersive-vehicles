package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Parse/validate coverage for {@code -Ddiv.experimentalThrottleRampTicks} (ticket comment 53115:
 * "bounded OPT-IN experimental one-second (20 physics ticks) full-scale throttle-rise candidate,
 * configurable/bypassable, no default change for other sessions/vehicles"). 0 means bypassed -
 * every value that isn't a valid positive in-bound integer must resolve to 0, never some other
 * active default, since the only safe fallback for an unreviewed experimental feature is off.
 */
class PhysicsBridgeExperimentalThrottleRampTicksTest {
 @Test void nullPropertyIsBypassed() {
  assertEquals(0, PhysicsBridge.resolveExperimentalThrottleRampTicks(null));
 }
 @Test void blankOrNonNumericIsBypassed() {
  assertEquals(0, PhysicsBridge.resolveExperimentalThrottleRampTicks(""));
  assertEquals(0, PhysicsBridge.resolveExperimentalThrottleRampTicks("not-a-number"));
  assertEquals(0, PhysicsBridge.resolveExperimentalThrottleRampTicks("NaN"));
  assertEquals(0, PhysicsBridge.resolveExperimentalThrottleRampTicks("Infinity"));
 }
 @Test void zeroOrNegativeIsBypassed() {
  assertEquals(0, PhysicsBridge.resolveExperimentalThrottleRampTicks("0"));
  assertEquals(0, PhysicsBridge.resolveExperimentalThrottleRampTicks("-20"));
 }
 @Test void aboveTheDocumentedUpperBoundIsBypassed() {
  assertEquals(0, PhysicsBridge.resolveExperimentalThrottleRampTicks(Integer.toString(PhysicsBridge.MAX_EXPERIMENTAL_THROTTLE_RAMP_TICKS+1)));
 }
 @Test void exactlyTheUpperBoundIsAccepted() {
  assertEquals(PhysicsBridge.MAX_EXPERIMENTAL_THROTTLE_RAMP_TICKS,
    PhysicsBridge.resolveExperimentalThrottleRampTicks(Integer.toString(PhysicsBridge.MAX_EXPERIMENTAL_THROTTLE_RAMP_TICKS)));
 }
 @Test void theRequestedOneSecondCandidateIsAccepted() {
  assertEquals(20, PhysicsBridge.resolveExperimentalThrottleRampTicks("20"));
 }
}
