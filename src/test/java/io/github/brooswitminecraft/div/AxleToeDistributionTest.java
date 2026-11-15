package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/**
 * MINECRAFT-533: {@link AxleToeDistribution#toeRadians} is the pure-math core of the configurable
 * per-axle toe alignment - covers the ticket's own named acceptance shape: mirrored left/right
 * symmetry (so a symmetric vehicle gets no unintended net lateral force at a symmetric state),
 * axle classification (reusing {@link BrakeBiasDistribution}'s own front/rear geometry), the
 * ambiguous-layout fallback, and unset/zero no-op equivalence.
 */
class AxleToeDistributionTest {
 private static final double[] X4 = {1.0, -1.0, 1.0, -1.0};
 private static final double[] Z4 = {1.5, 1.5, -1.5, -1.5}; // front axle (0,1), rear axle (2,3)
 @Test void zeroWheelsReturnsEmpty() {
  assertEquals(0, AxleToeDistribution.toeRadians(new double[0], new double[0], 2.0, -2.0).length);
 }
 @Test void bothFieldsUnsetIsAPureNoOpForAnyLayout() {
  double[] toe = AxleToeDistribution.toeRadians(X4, Z4, null, null);
  for (double t : toe) assertEquals(0.0, t, 0.0, "unset toe on both axles must be an exact zero no-op");
 }
 @Test void frontOnlyConfiguredLeavesTheRearAxleAtExactlyZero() {
  // An axle left unset within an otherwise well-defined layout is simply zero for that axle -
  // not the same thing as the ambiguous-layout fallback below.
  double[] toe = AxleToeDistribution.toeRadians(X4, Z4, 4.0, null);
  assertEquals(0.0, toe[2], 0.0); assertEquals(0.0, toe[3], 0.0);
  assertNotEquals(0.0, toe[0]); assertNotEquals(0.0, toe[1]);
 }
 @Test void mirroredLeftRightSymmetryProducesOppositeSignedOffsetsOfEqualMagnitude() {
  double[] toe = AxleToeDistribution.toeRadians(X4, Z4, 5.0, -3.0);
  // front axle: wheel 0 at x=+1, wheel 1 at x=-1 - equal magnitude, opposite sign.
  assertEquals(-toe[1], toe[0], 1e-12, "front-axle left/right must be exact mirror images");
  // rear axle: wheel 2 at x=+1, wheel 3 at x=-1 - same mirrored shape, independent magnitude.
  assertEquals(-toe[3], toe[2], 1e-12, "rear-axle left/right must be exact mirror images");
 }
 @Test void positiveToeDegreesConvergesBothSidesTowardTheCenterline() {
  // Toe-IN (positive): the right-side wheel (x>0) steers toward the centerline (negative
  // offset), the left-side wheel (x<0) steers toward the centerline too (positive offset).
  double[] toe = AxleToeDistribution.toeRadians(X4, Z4, 6.0, 0.0);
  assertTrue(toe[0] < 0, "right-side front wheel must angle toward the centerline on positive (toe-in) front toe");
  assertTrue(toe[1] > 0, "left-side front wheel must angle toward the centerline on positive (toe-in) front toe");
 }
 @Test void negativeToeDegreesDivergesBothSidesAwayFromTheCenterline() {
  double[] toe = AxleToeDistribution.toeRadians(X4, Z4, -6.0, 0.0);
  assertTrue(toe[0] > 0, "right-side front wheel must angle away from the centerline on negative (toe-out) front toe");
  assertTrue(toe[1] < 0, "left-side front wheel must angle away from the centerline on negative (toe-out) front toe");
 }
 @Test void degreesAreConvertedToRadiansExactly() {
  // Two wheels on distinguishable axles (so this is NOT the ambiguous single-axle fallback) -
  // wheel 0 (x=1) is on the front (max-z) axle.
  double[] toe = AxleToeDistribution.toeRadians(new double[]{1.0, -1.0}, new double[]{0.0, -5.0}, 10.0, null);
  assertEquals(-Math.toRadians(10.0), toe[0], 1e-15);
  assertEquals(0.0, toe[1], 0.0, "rear toe is unset for this axle");
 }
 @Test void wheelExactlyOnTheCenterlineGetsNoToeRegardlessOfConfiguredMagnitude() {
  double[] toe = AxleToeDistribution.toeRadians(new double[]{0.0, 1.0}, new double[]{1.5, -1.5}, 8.0, 8.0);
  assertEquals(0.0, toe[0], 0.0, "x==0 has no 'other side' to mirror against - signum(0)==0 yields no toe");
 }
 @Test void reversedLayoutStillPicksTheGeometricallyForwardAxleAsFront() {
  // Same geometric-not-steering-based classification BrakeBiasDistributionTest covers - the
  // physically-forward wheels happen to sit at negative z here; the max-z wheels (indices 2,3)
  // must still be the ones classified "front" and get toeFrontDegrees, never indices 0,1.
  double[] x = {1.0, -1.0, 1.0, -1.0};
  double[] z = {-1.5, -1.5, 1.5, 1.5};
  double[] toe = AxleToeDistribution.toeRadians(x, z, 5.0, 0.0);
  assertEquals(0.0, toe[0], 0.0); assertEquals(0.0, toe[1], 0.0); // rear axle, toeRearDegrees=0
  assertEquals(-Math.toRadians(5.0), toe[2], 1e-12); // front axle, x=+1 -> toe-in toward centerline
  assertEquals(Math.toRadians(5.0), toe[3], 1e-12); // front axle, x=-1 -> toe-in toward centerline
 }
 @Test void allWheelsAtTheSameZFallsBackToNoToeRatherThanGuessingWhichAxleApplies() {
  double[] x = {1.0, -1.0, 1.0, -1.0};
  double[] z = {0.3, 0.3, 0.3, 0.3}; // single axle / side-by-side-only layout - ambiguous
  double[] toe = AxleToeDistribution.toeRadians(x, z, 10.0, 10.0);
  for (double t : toe) assertEquals(0.0, t, 0.0, "ambiguous (no distinguishable rear axle) layout must apply no toe at all");
 }
 @Test void aLoneWheelFallsBackToNoToe() {
  double[] toe = AxleToeDistribution.toeRadians(new double[]{1.0}, new double[]{0.0}, 10.0, 10.0);
  assertEquals(0.0, toe[0], 0.0);
 }
 @Test void multiAxleLayoutLumpsEveryNonFrontRowIntoOneRearGroupForToeToo() {
  // Three axles (front/mid/rear) - mid+rear together form "rear", same shape as
  // BrakeBiasDistributionTest's own multi-axle case.
  double[] x = {1.0, -1.0, 1.0, -1.0, 1.0, -1.0};
  double[] z = {2.0, 2.0, 0.0, 0.0, -2.0, -2.0};
  double[] toe = AxleToeDistribution.toeRadians(x, z, 4.0, -2.0);
  double expectedFront = Math.toRadians(4.0), expectedRear = Math.toRadians(-2.0);
  assertEquals(-expectedFront, toe[0], 1e-12); assertEquals(expectedFront, toe[1], 1e-12);
  for (int i = 2; i < 6; i++) {
   double expected = (i % 2 == 0) ? -expectedRear : expectedRear;
   assertEquals(expected, toe[i], 1e-12, "wheel " + i + " is part of the lumped rear group");
  }
 }
 @Test void everyOffsetIsAlwaysFiniteAcrossManyRandomLayoutsAndToeValues() {
  java.util.Random rng = new java.util.Random(53301);
  for (int trial = 0; trial < 500; trial++) {
   int n = 1 + rng.nextInt(8);
   double[] x = new double[n], z = new double[n];
   for (int i = 0; i < n; i++) { x[i] = rng.nextDouble() * 4 - 2; z[i] = rng.nextDouble() * 4 - 2; }
   Double front = rng.nextBoolean() ? null : rng.nextDouble() * 20 - 10;
   Double rear = rng.nextBoolean() ? null : rng.nextDouble() * 20 - 10;
   double[] toe = AxleToeDistribution.toeRadians(x, z, front, rear);
   assertEquals(n, toe.length);
   for (double t : toe) assertTrue(Double.isFinite(t), "trial " + trial + ": offset must be finite, got " + t);
  }
 }
}
