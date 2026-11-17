package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MINECRAFT-545/547 (John's comments 54486/54494). Pure coverage, no ServerLevel: (1)
 * {@link BuoyantForces#scanBottomY}/{@link BuoyantForces#maxPossibleIterations} - the scan's own
 * bounds/capacity arithmetic; (2) {@link BuoyantForces#scanForWater(int, BuoyantForces.FluidHeightLookup)}
 * - the loop/early-return/iteration-counting logic itself, exercised through a fake lookup (the
 * callback seam 54494 asked for) so early return and the REAL per-call iteration count (not the
 * scan's maximum capacity) are both verified directly, not merely asserted.
 */
class BuoyantForcesWaterScanBoundsTest {
 @Test void bottomYIs24BelowFromYFloor() {
  assertEquals(100 - 24, BuoyantForces.scanBottomY(100));
  assertEquals(0, BuoyantForces.scanBottomY(24));
  assertEquals(-5, BuoyantForces.scanBottomY(19));
 }

 @Test void maxPossibleIterationsIs25ForTheScansOwn24BlockSpan() {
  // fromYFloor down to fromYFloor-24, BOTH ends inclusive - 25 distinct Y values, not 24.
  int fromYFloor = 106;
  int bottom = BuoyantForces.scanBottomY(fromYFloor);
  assertEquals(82, bottom);
  assertEquals(25, BuoyantForces.maxPossibleIterations(fromYFloor, bottom));
 }

 @Test void maxPossibleIterationsMatchesDirectEnumerationForSeveralFromYValues() {
  for (int fromYFloor : new int[]{0, 1, 24, 100, 259}) {
   int bottom = BuoyantForces.scanBottomY(fromYFloor);
   int directCount = 0;
   for (int y = fromYFloor; y >= bottom; y--) directCount++;
   assertEquals(directCount, BuoyantForces.maxPossibleIterations(fromYFloor, bottom));
  }
 }

 @Test void earlyWaterMatchStopsImmediatelyWithIterationsEqualToOne() {
  // Water found on the very FIRST y checked (fromYFloor itself) - the real iteration count must
  // be 1, not the scan's own 25-value maximum capacity (the exact bug 54494 flagged).
  var result = BuoyantForces.scanForWater(100, y -> y == 100 ? 3.5 : null);
  assertTrue(result.found());
  assertEquals(100, result.foundBlockY());
  assertEquals(3.5, result.fluidHeight());
  assertEquals(103.5, result.surfaceY());
  assertEquals(1, result.iterations());
 }

 @Test void lateWaterMatchCountsOnlyTheIterationsActuallyPerformed() {
  // Water found on the 10th y checked (fromYFloor-9) - iterations must be exactly 10, not 25.
  int fromYFloor = 100;
  var result = BuoyantForces.scanForWater(fromYFloor, y -> y == fromYFloor - 9 ? 1.0 : null);
  assertTrue(result.found());
  assertEquals(fromYFloor - 9, result.foundBlockY());
  assertEquals(10, result.iterations());
 }

 @Test void noWaterFoundVisitsTheFullMaxPossibleIterationsAndReportsNegativeInfinity() {
  var result = BuoyantForces.scanForWater(100, y -> null);
  assertFalse(result.found());
  assertEquals(Double.NEGATIVE_INFINITY, result.surfaceY());
  assertEquals(BuoyantForces.maxPossibleIterations(100, BuoyantForces.scanBottomY(100)), result.iterations());
 }

 @Test void lookupIsNeverCalledBelowTheScansOwnBottomBound() {
  // Confirms the loop itself still respects the original bound - a fake lookup that would throw
  // outside [bottomY, fromYFloor] never fires if called out of range.
  int fromYFloor = 50, bottom = BuoyantForces.scanBottomY(fromYFloor);
  var result = BuoyantForces.scanForWater(fromYFloor, y -> {
   if (y < bottom || y > fromYFloor) throw new AssertionError("queried out of range: " + y);
   return null;
  });
  assertFalse(result.found());
 }
}
