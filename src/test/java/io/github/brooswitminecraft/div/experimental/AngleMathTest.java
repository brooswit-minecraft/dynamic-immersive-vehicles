package io.github.brooswitminecraft.div.experimental;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Review finding (John, Jira comment 53717/MINECRAFT-518): the exact boundary-wrap regression
 * - a naive angle difference miscounts crossing the +-180 degree boundary as a huge jump instead
 * of the small rotation it actually is. */
class AngleMathTest {
 @Test void simpleDeltaWithNoWrapIsExact() {
  assertEquals(5.0, AngleMath.shortestDeltaDegrees(10, 15), 1e-9);
  assertEquals(-5.0, AngleMath.shortestDeltaDegrees(15, 10), 1e-9);
 }

 @Test void theExactReviewRegressionCase() {
  // Review's own example: 179 to -179 is a 2-degree rotation, not 358.
  assertEquals(2.0, AngleMath.shortestDeltaDegrees(179, -179), 1e-9);
  assertEquals(-2.0, AngleMath.shortestDeltaDegrees(-179, 179), 1e-9);
 }

 @Test void crossingTheBoundaryFromEitherDirection() {
  assertEquals(4.0, AngleMath.shortestDeltaDegrees(178, -178), 1e-9);
  assertEquals(-4.0, AngleMath.shortestDeltaDegrees(-178, 178), 1e-9);
  assertEquals(1.0, AngleMath.shortestDeltaDegrees(179.5, -179.5), 1e-9);
 }

 @Test void exactlyOppositeAnglesReturnPlusOrMinus180() {
  double delta = AngleMath.shortestDeltaDegrees(0, 180);
  assertEquals(180.0, Math.abs(delta), 1e-9);
 }

 @Test void zeroDeltaWhenAnglesAreEqual() {
  assertEquals(0.0, AngleMath.shortestDeltaDegrees(42, 42), 1e-9);
  assertEquals(0.0, AngleMath.shortestDeltaDegrees(-179, -179), 1e-9);
 }

 @Test void resultIsAlwaysWithinOneHundredEightyDegrees() {
  // Sweep a wide range of from/to pairs, including many multiples of 360 beyond a single wrap,
  // and confirm the result never exceeds the shortest-path bound.
  for (double from = -720; from <= 720; from += 37) {
   for (double to = -720; to <= 720; to += 53) {
    double delta = AngleMath.shortestDeltaDegrees(from, to);
    assertTrue(Math.abs(delta) <= 180.0 + 1e-9,
      "delta=" + delta + " out of bounds for from=" + from + " to=" + to);
   }
  }
 }

 @Test void repeatedlyAccumulatingSmallStepsAcrossTheBoundaryNeverInflatesActivity() {
  // Simulates exactly what BuoyancyAngularDragProbe.measure now does: a hull oscillating back
  // and forth across the +-180 boundary in small real steps must accumulate only the real
  // rotation, not a wrap-inflated one - the actual defect review's worked example (3015 degrees
  // over 140 ticks for a hull that should be settling) was traced to.
  double[] rollSequence = {178, 179.5, -179.5, -178, -179.5, 179.5, 178};
  double cumulative = 0;
  for (int i = 1; i < rollSequence.length; i++) {
   cumulative += Math.abs(AngleMath.shortestDeltaDegrees(rollSequence[i - 1], rollSequence[i]));
  }
  // Real path: 178->179.5 (1.5), 179.5->-179.5 (1), -179.5->-178 (1.5), -178->-179.5 (1.5),
  // -179.5->179.5 (1), 179.5->178 (1.5) = 8.0 total - tiny oscillation near the boundary, not
  // hundreds of degrees the naive (unwrapped) difference would have produced.
  assertEquals(8.0, cumulative, 1e-9);
 }
}
