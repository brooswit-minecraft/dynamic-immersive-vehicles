package io.github.brooswitminecraft.div;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WheelAxisCalibrationTest {
 @Test void disabledAxisIsAlwaysZero() {
  assertEquals(0.0, WheelAxisCalibration.magnitude(1.0, -1.0, -1.0, false, 0.03));
 }
 @Test void disconnectedOrNonFiniteReadingIsZero() {
  assertEquals(0.0, WheelAxisCalibration.magnitude(Double.NaN, -1.0, 1.0, false, 0.03));
  assertEquals(0.0, WheelAxisCalibration.magnitude(Double.POSITIVE_INFINITY, -1.0, 1.0, false, 0.03));
 }
 @Test void restAndFullPullMapToZeroAndOne() {
  assertEquals(0.0, WheelAxisCalibration.magnitude(-1.0, -1.0, 1.0, false, 0.03), 1e-9);
  assertEquals(1.0, WheelAxisCalibration.magnitude(1.0, -1.0, 1.0, false, 0.03), 1e-9);
  assertEquals(0.5, WheelAxisCalibration.magnitude(0.0, -1.0, 1.0, false, 0.03), 1e-9);
 }
 @Test void deadzoneNearRestIsZero() {
  assertEquals(0.0, WheelAxisCalibration.magnitude(-0.98, -1.0, 1.0, false, 0.03));
  assertTrue(WheelAxisCalibration.magnitude(-0.9, -1.0, 1.0, false, 0.03) > 0.0);
 }
 @Test void invertFlipsWhichEndIsPulled() {
  assertEquals(1.0, WheelAxisCalibration.magnitude(-1.0, -1.0, 1.0, true, 0.03), 1e-9);
  assertEquals(0.0, WheelAxisCalibration.magnitude(1.0, -1.0, 1.0, true, 0.03), 1e-9);
 }
 @Test void outOfRangeRawIsClampedNotExtrapolated() {
  assertEquals(1.0, WheelAxisCalibration.magnitude(5.0, -1.0, 1.0, false, 0.03), 1e-9);
  assertEquals(0.0, WheelAxisCalibration.magnitude(-5.0, -1.0, 1.0, false, 0.03), 1e-9);
 }
}
