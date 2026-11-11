package io.github.brooswitminecraft.div.experimental;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Semantics coverage for the candidate {@link DriveRateLimiter} (ticket comment 53113's explicit
 * ask: release/reverse/brake semantics must be defined, not just a forward ramp). */
class DriveRateLimiterTest {
 @Test void forwardSlamIsCappedAtMaxDeltaPerTick() {
  var s = new DriveRateLimiter.State();
  assertEquals(100, DriveRateLimiter.apply(s, 8000, 100));
  assertEquals(200, DriveRateLimiter.apply(s, 8000, 100));
  assertEquals(300, DriveRateLimiter.apply(s, 8000, 100));
 }
 @Test void commandedBelowAlreadyRealizedIsNeverLimited() {
  var s = new DriveRateLimiter.State();
  DriveRateLimiter.apply(s, 8000, 100); // forward=100
  DriveRateLimiter.apply(s, 8000, 100); // forward=200
  assertEquals(0, DriveRateLimiter.apply(s, 0, 100), "releasing throttle must be instant, not rate-limited");
 }
 @Test void reversalFromForwardDropsForwardInstantlyAndRampsReverseFromZero() {
  var s = new DriveRateLimiter.State();
  DriveRateLimiter.apply(s, 8000, 100); // forward=100
  DriveRateLimiter.apply(s, 8000, 100); // forward=200
  double result = DriveRateLimiter.apply(s, -8000, 100);
  assertEquals(-100, result, "slamming into reverse must get the SAME rate limit as a forward slam from a stop, not pass through unlimited");
  assertEquals(0, s.forward(), "forward effort releases instantly on reversal");
  assertEquals(100, s.reverse());
 }
 @Test void reverseSlamIsAlsoCapped() {
  var s = new DriveRateLimiter.State();
  assertEquals(-50, DriveRateLimiter.apply(s, -8000, 50));
  assertEquals(-100, DriveRateLimiter.apply(s, -8000, 50));
 }
 @Test void commandedAtOrBelowAlreadyRealizedPassesThroughExactly() {
  var s = new DriveRateLimiter.State();
  DriveRateLimiter.apply(s, 150, 100); // forward=100 (capped below commanded 150)
  assertEquals(90, DriveRateLimiter.apply(s, 90, 100), "a partial release to a value still above zero but below realized must be instant");
 }
 @Test void zeroCommandedAfterIdleStaysZero() {
  var s = new DriveRateLimiter.State();
  assertEquals(0, DriveRateLimiter.apply(s, 0, 100));
 }
}
