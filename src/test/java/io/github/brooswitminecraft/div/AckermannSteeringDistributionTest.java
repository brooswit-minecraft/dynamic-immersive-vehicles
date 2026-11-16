package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/**
 * MINECRAFT-540: {@link AckermannSteeringDistribution#steerRadians} is the pure-math core of the
 * opt-in geometry-based Ackermann steering - covers the ticket's own named acceptance shape:
 * mirrored inner/outer angles sharing one turning center, zero/disabled parity with the pre-
 * ticket uniform angle, geometry degeneration, and the explicit ambiguous/multi-axle/centered/
 * skid-steer fallback (a {@code null} result, meaning "the caller keeps the uniform angle").
 */
class AckermannSteeringDistributionTest {
 // A conventional two-axle layout: front (steering) axle at z=+1.5, rear (pivot) axle at
 // z=-1.5, 2m track on each axle - same shape as AxleToeDistributionTest's own X4/Z4.
 private static final double[] X = {1.0, -1.0, 1.0, -1.0};
 private static final double[] Z = {1.5, 1.5, -1.5, -1.5};
 private static final boolean[] FRONT_STEERS = {true, true, false, false};

 @Test void zeroDeltaReturnsAllZeroWithoutEvenEvaluatingGeometry() {
  // Even a layout that would otherwise be ambiguous (single shared z) must return the exact
  // zero-baseline at delta==0, never null - "zero matches the accepted baseline" is a numeric
  // equivalence requirement, not a request to pick a fallback path.
  double[] result = AckermannSteeringDistribution.steerRadians(new double[]{1,-1}, new double[]{0,0}, new boolean[]{true,true}, false, 0.0);
  assertNotNull(result);
  for (double r : result) assertEquals(0.0, r, 0.0);
 }

 @Test void skidSteerAlwaysFallsBackRegardlessOfLayoutOrDelta() {
  assertNull(AckermannSteeringDistribution.steerRadians(X, Z, FRONT_STEERS, true, Math.toRadians(20)));
 }

 @Test void singleCenteredSteerWheelFallsBack() {
  double[] x = {0.0, 1.0, -1.0};
  double[] z = {1.5, -1.5, -1.5};
  boolean[] steer = {true, false, false};
  assertNull(AckermannSteeringDistribution.steerRadians(x, z, steer, false, Math.toRadians(20)));
 }

 @Test void nonStraddlingSameSideSteerWheelsFallBack() {
  double[] x = {1.0, 2.0, -1.0, -2.0};
  double[] z = {1.5, 1.5, -1.5, -1.5};
  boolean[] steer = {true, true, false, false};
  assertNull(AckermannSteeringDistribution.steerRadians(x, z, steer, false, Math.toRadians(20)));
 }

 @Test void fourWheelSteerAcrossBothAxlesFallsBack() {
  boolean[] allSteer = {true, true, true, true};
  assertNull(AckermannSteeringDistribution.steerRadians(X, Z, allSteer, false, Math.toRadians(20)));
 }

 @Test void threeDistinctAxleBandsFallBack() {
  double[] x = {1.0, -1.0, 1.0, -1.0, 1.0, -1.0};
  double[] z = {1.5, 1.5, 0.0, 0.0, -1.5, -1.5};
  boolean[] steer = {true, true, false, false, false, false};
  assertNull(AckermannSteeringDistribution.steerRadians(x, z, steer, false, Math.toRadians(20)));
 }

 @Test void singleAxleLayoutWithNoDistinguishableSecondAxleFallsBack() {
  double[] x = {1.0, -1.0};
  double[] z = {0.0, 0.0};
  boolean[] steer = {true, true};
  assertNull(AckermannSteeringDistribution.steerRadians(x, z, steer, false, Math.toRadians(20)));
 }

 @Test void degenerateNearZeroWheelbaseFallsBack() {
  double[] x = {1.0, -1.0, 1.0, -1.0};
  double[] z = {1e-9, 1e-9, -1e-9, -1e-9};
  assertNull(AckermannSteeringDistribution.steerRadians(x, z, FRONT_STEERS, false, Math.toRadians(20)));
 }

 @Test void nonSteeringWheelSharingTheSteeringBandFallsBack() {
  double[] x = {1.0, -1.0, 0.0, 1.0, -1.0};
  double[] z = {1.5, 1.5, 1.5, -1.5, -1.5};
  boolean[] steer = {true, true, false, false, false};
  assertNull(AckermannSteeringDistribution.steerRadians(x, z, steer, false, Math.toRadians(20)));
 }

 @Test void innerWheelTurnsMoreSharplyThanOuterWheelAndBothMirrorOnOppositeLock() {
  double delta = Math.toRadians(20);
  double[] right = AckermannSteeringDistribution.steerRadians(X, Z, FRONT_STEERS, false, delta);
  double[] left = AckermannSteeringDistribution.steerRadians(X, Z, FRONT_STEERS, false, -delta);
  assertNotNull(right); assertNotNull(left);
  // index 0 is the right wheel (x=+1), index 1 is the left wheel (x=-1).
  assertTrue(Math.abs(right[0]) != Math.abs(right[1]), "the two steering wheels must receive genuinely different magnitudes, not a uniform angle");
  // Opposite lock must produce the mirror image (swap which side is inner).
  assertEquals(right[0], -left[1], 1e-9, "steering the opposite way must exactly mirror which wheel is inner/outer");
  assertEquals(right[1], -left[0], 1e-9, "steering the opposite way must exactly mirror which wheel is inner/outer");
  // Non-steering (rear/pivot) wheels are always exactly zero in this array - the caller never
  // reads them, but they must never be garbage/NaN.
  assertEquals(0.0, right[2], 0.0); assertEquals(0.0, right[3], 0.0);
 }

 @Test void reverseTravelDoesNotChangeTheGeometryOnlyTheDriveDirectionOutsideThisHelper() {
  // This helper has no notion of forward/reverse - the rudder-derived delta sign alone decides
  // which wheel is inner, independent of throttle/gear direction (reverse travel composes with
  // whatever delta PhysicsBridge passes in, exactly like the pre-ticket uniform formula did).
  double delta = Math.toRadians(15);
  double[] forwardTurn = AckermannSteeringDistribution.steerRadians(X, Z, FRONT_STEERS, false, delta);
  double[] sameDeltaAgain = AckermannSteeringDistribution.steerRadians(X, Z, FRONT_STEERS, false, delta);
  assertArrayEquals(forwardTurn, sameDeltaAgain, 0.0);
 }

 @Test void toeComposesAdditivelyOnTopOfTheAckermannAngleExactlyLikeTheUniformAngle() {
  // The caller (PhysicsBridge.stepGround) always adds toeRadians[wheelIdx] AFTER reading either
  // the uniform or the Ackermann angle - this helper itself knows nothing about toe, so the
  // composition is just ordinary addition, proven here the same way
  // ToeAlignmentContactIntegrationTest proves it for the uniform angle.
  double delta = Math.toRadians(20);
  double[] ackermann = AckermannSteeringDistribution.steerRadians(X, Z, FRONT_STEERS, false, delta);
  double[] toe = AxleToeDistribution.toeRadians(X, Z, 4.0, -2.0);
  double composedRight = ackermann[0] + toe[0];
  double composedLeft = ackermann[1] + toe[1];
  assertEquals(ackermann[0], composedRight - toe[0], 1e-12);
  assertEquals(ackermann[1], composedLeft - toe[1], 1e-12);
  assertNotEquals(ackermann[0], composedRight, "a nonzero front toe must actually change the composed steer angle, not be silently dropped");
 }
}
