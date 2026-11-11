package io.github.brooswitminecraft.div.experimental;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Integration-level coverage for {@link DriveRateLimiter#shape} (ticket comment 53115's
 * explicit test list: brake-held+throttle, repeated on/off pulses, reverse, and a full-command
 * steady value) - the exact composition {@code Body.stepGround} calls when the experimental
 * throttle-rate-rise candidate is enabled. */
class DriveRateLimiterShapeTest {
 @Test void brakeHeldWhileThrottleAppliedNeverAccumulatesHiddenEffort() {
  var s = new DriveRateLimiter.State();
  for (int i=0;i<50;i++) {
   double out = DriveRateLimiter.shape(s, 8000, true, 400);
   assertEquals(0, out, "braking must reset stored effort every tick it's held, regardless of commanded drive");
  }
  assertEquals(0, s.forward());
 }
 @Test void releasingBrakeAfterBeingHeldStartsTheRampFromZeroNotFromAPrechargedValue() {
  var s = new DriveRateLimiter.State();
  for (int i=0;i<50;i++) DriveRateLimiter.shape(s, 8000, true, 400); // held brake + full throttle commanded the whole time
  double firstTickAfterRelease = DriveRateLimiter.shape(s, 8000, false, 400);
  assertEquals(400, firstTickAfterRelease, "must start ramping from zero on brake release, not reveal a precharged effort");
 }
 @Test void repeatedOnOffThrottlePulsesEachRestartTheRampFromWhereTheyLeftOff() {
  var s = new DriveRateLimiter.State();
  assertEquals(400, DriveRateLimiter.shape(s, 8000, false, 400));
  assertEquals(0, DriveRateLimiter.shape(s, 0, false, 400), "release is instant");
  assertEquals(400, DriveRateLimiter.shape(s, 8000, false, 400), "a fresh pulse ramps from zero again, not from the earlier high-water mark");
 }
 @Test void reverseUnderNoBrakeRampsFromZeroEvenAfterForwardEffort() {
  var s = new DriveRateLimiter.State();
  DriveRateLimiter.shape(s, 8000, false, 400);
  DriveRateLimiter.shape(s, 8000, false, 400); // forward=800
  double result = DriveRateLimiter.shape(s, -8000, false, 400);
  assertEquals(-400, result, "reversal gets the same rate limit as a forward slam from a stop");
 }
 @Test void fullCommandSteadyValueRampsMonotonicallyToCommandedThenHolds() {
  var s = new DriveRateLimiter.State();
  double last=0;
  for (int i=0;i<25;i++) {
   double out = DriveRateLimiter.shape(s, 8000, false, 400);
   assertTrue(out >= last, "a steady full-command ramp must be monotonically non-decreasing");
   last = out;
  }
  assertEquals(8000, last, "after enough ticks at the configured rate, the ramp must reach the steady commanded value exactly");
 }
}
