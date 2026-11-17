package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MINECRAFT-545/547 (John's comment 54486: "Current loop uses y>=bottom with
 * bottom=floor(fromY)-24: inclusive endpoints, up to 25 visited Y values; report accurately,
 * don't change bounds"). Pure arithmetic coverage of {@link BuoyantForces#scanBottomY} and
 * {@link BuoyantForces#scanVisitedCount} alone - no ServerLevel/fluid lookup involved, since
 * those two functions never touch one.
 */
class BuoyantForcesWaterScanBoundsTest {
 @Test void bottomYIs24BelowFromYFloor() {
  assertEquals(100 - 24, BuoyantForces.scanBottomY(100));
  assertEquals(0, BuoyantForces.scanBottomY(24));
  assertEquals(-5, BuoyantForces.scanBottomY(19));
 }

 @Test void visitedCountIs25ForTheScansOwn24BlockSpan() {
  // fromYFloor down to fromYFloor-24, BOTH ends inclusive - 25 distinct Y values, not 24.
  int fromYFloor = 106;
  int bottom = BuoyantForces.scanBottomY(fromYFloor);
  assertEquals(82, bottom);
  assertEquals(25, BuoyantForces.scanVisitedCount(fromYFloor, bottom));
 }

 @Test void visitedCountMatchesDirectEnumerationForSeveralFromYValues() {
  for (int fromYFloor : new int[]{0, 1, 24, 100, 259}) {
   int bottom = BuoyantForces.scanBottomY(fromYFloor);
   int directCount = 0;
   for (int y = fromYFloor; y >= bottom; y--) directCount++;
   assertEquals(directCount, BuoyantForces.scanVisitedCount(fromYFloor, bottom));
  }
 }
}
