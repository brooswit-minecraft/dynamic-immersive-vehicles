package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/**
 * MINECRAFT-540: {@link AckermannSteeringDistribution#steerRadians} is the pure-math core of the
 * opt-in geometry-based Ackermann steering - covers the ticket's own named acceptance shape:
 * mirrored inner/outer angles sharing one turning center, zero/disabled parity with the pre-
 * ticket uniform angle, geometry degeneration, the full-lock clamp (Wroosbit review, comment
 * 54164), and the explicit ambiguous/multi-axle/centered/skid-steer fallback (a {@code null}
 * result, meaning "the caller keeps the uniform angle").
 */
class AckermannSteeringDistributionTest {
 // A conventional two-axle layout: front (steering) axle at z=+1.5, rear (pivot) axle at
 // z=-1.5, 2m track on each axle - same shape as AxleToeDistributionTest's own X4/Z4.
 private static final double[] X = {1.0, -1.0, 1.0, -1.0};
 private static final double[] Z = {1.5, 1.5, -1.5, -1.5};
 private static final boolean[] FRONT_STEERS = {true, true, false, false};
 // A generous ceiling that never clamps ordinary (20-degree-class) steering - only the
 // dedicated full-lock-clamp tests below pick a tight enough bound to actually trigger it.
 private static final double GENEROUS_LOCK = Math.toRadians(60);

 @Test void zeroDeltaReturnsAllZeroWithoutEvenEvaluatingGeometry() {
  // Even a layout that would otherwise be ambiguous (single shared z) must return the exact
  // zero-baseline at delta==0, never null - "zero matches the accepted baseline" is a numeric
  // equivalence requirement, not a request to pick a fallback path.
  double[] result = AckermannSteeringDistribution.steerRadians(new double[]{1,-1}, new double[]{0,0}, new boolean[]{true,true}, false, 0.0, GENEROUS_LOCK);
  assertNotNull(result);
  for (double r : result) assertEquals(0.0, r, 0.0);
 }

 @Test void skidSteerAlwaysFallsBackRegardlessOfLayoutOrDelta() {
  assertNull(AckermannSteeringDistribution.steerRadians(X, Z, FRONT_STEERS, true, Math.toRadians(20), GENEROUS_LOCK));
 }

 @Test void singleCenteredSteerWheelFallsBack() {
  double[] x = {0.0, 1.0, -1.0};
  double[] z = {1.5, -1.5, -1.5};
  boolean[] steer = {true, false, false};
  assertNull(AckermannSteeringDistribution.steerRadians(x, z, steer, false, Math.toRadians(20), GENEROUS_LOCK));
 }

 @Test void nonStraddlingSameSideSteerWheelsFallBack() {
  double[] x = {1.0, 2.0, -1.0, -2.0};
  double[] z = {1.5, 1.5, -1.5, -1.5};
  boolean[] steer = {true, true, false, false};
  assertNull(AckermannSteeringDistribution.steerRadians(x, z, steer, false, Math.toRadians(20), GENEROUS_LOCK));
 }

 @Test void fourWheelSteerAcrossBothAxlesFallsBack() {
  boolean[] allSteer = {true, true, true, true};
  assertNull(AckermannSteeringDistribution.steerRadians(X, Z, allSteer, false, Math.toRadians(20), GENEROUS_LOCK));
 }

 @Test void threeDistinctAxleBandsFallBack() {
  double[] x = {1.0, -1.0, 1.0, -1.0, 1.0, -1.0};
  double[] z = {1.5, 1.5, 0.0, 0.0, -1.5, -1.5};
  boolean[] steer = {true, true, false, false, false, false};
  assertNull(AckermannSteeringDistribution.steerRadians(x, z, steer, false, Math.toRadians(20), GENEROUS_LOCK));
 }

 @Test void singleAxleLayoutWithNoDistinguishableSecondAxleFallsBack() {
  double[] x = {1.0, -1.0};
  double[] z = {0.0, 0.0};
  boolean[] steer = {true, true};
  assertNull(AckermannSteeringDistribution.steerRadians(x, z, steer, false, Math.toRadians(20), GENEROUS_LOCK));
 }

 @Test void degenerateNearZeroWheelbaseFallsBack() {
  double[] x = {1.0, -1.0, 1.0, -1.0};
  double[] z = {1e-9, 1e-9, -1e-9, -1e-9};
  assertNull(AckermannSteeringDistribution.steerRadians(x, z, FRONT_STEERS, false, Math.toRadians(20), GENEROUS_LOCK));
 }

 @Test void nonSteeringWheelSharingTheSteeringBandFallsBack() {
  double[] x = {1.0, -1.0, 0.0, 1.0, -1.0};
  double[] z = {1.5, 1.5, 1.5, -1.5, -1.5};
  boolean[] steer = {true, true, false, false, false};
  assertNull(AckermannSteeringDistribution.steerRadians(x, z, steer, false, Math.toRadians(20), GENEROUS_LOCK));
 }

 @Test void innerWheelTurnsMoreSharplyThanOuterWheelAndBothMirrorOnOppositeLock() {
  double delta = Math.toRadians(20);
  double[] right = AckermannSteeringDistribution.steerRadians(X, Z, FRONT_STEERS, false, delta, GENEROUS_LOCK);
  double[] left = AckermannSteeringDistribution.steerRadians(X, Z, FRONT_STEERS, false, -delta, GENEROUS_LOCK);
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
  double[] forwardTurn = AckermannSteeringDistribution.steerRadians(X, Z, FRONT_STEERS, false, delta, GENEROUS_LOCK);
  double[] sameDeltaAgain = AckermannSteeringDistribution.steerRadians(X, Z, FRONT_STEERS, false, delta, GENEROUS_LOCK);
  assertArrayEquals(forwardTurn, sameDeltaAgain, 0.0);
 }

 @Test void toeComposesAdditivelyOnTopOfTheAckermannAngleExactlyLikeTheUniformAngle() {
  // The caller (PhysicsBridge.stepGround) always adds toeRadians[wheelIdx] AFTER reading either
  // the uniform or the Ackermann angle - this helper itself knows nothing about toe, so the
  // composition is just ordinary addition, proven here the same way
  // ToeAlignmentContactIntegrationTest proves it for the uniform angle.
  double delta = Math.toRadians(20);
  double[] ackermann = AckermannSteeringDistribution.steerRadians(X, Z, FRONT_STEERS, false, delta, GENEROUS_LOCK);
  double[] toe = AxleToeDistribution.toeRadians(X, Z, 4.0, -2.0);
  double composedRight = ackermann[0] + toe[0];
  double composedLeft = ackermann[1] + toe[1];
  assertEquals(ackermann[0], composedRight - toe[0], 1e-12);
  assertEquals(ackermann[1], composedLeft - toe[1], 1e-12);
  assertNotEquals(ackermann[0], composedRight, "a nonzero front toe must actually change the composed steer angle, not be silently dropped");
 }

 @Test void fullLockClampCapsAnInnerAngleThatWouldOtherwiseExceedTheConfiguredMaxSteer() {
  // Wroosbit review (comment 54164), reproduction 1: wheelbase L=3, half-track x=1, full-lock
  // delta=60 degrees (60 is Overrides's own accepted maxSteerDegrees ceiling) - the raw
  // Ackermann formula gives atan(3/(R-1))=76.29 degrees for the inner wheel, exceeding the
  // vehicle's own configured 60-degree lock. The clamped result must saturate at exactly 60
  // degrees in delta's own (positive) direction, never the unclamped 76.29.
  double delta = Math.toRadians(60);
  double maxLock = Math.toRadians(60);
  double[] result = AckermannSteeringDistribution.steerRadians(X, Z, FRONT_STEERS, false, delta, maxLock);
  assertNotNull(result);
  assertEquals(maxLock, result[0], 1e-9, "the inner wheel must saturate at the configured full lock, not the unclamped 76.29 degrees");
  assertTrue(Math.abs(result[1]) <= maxLock + 1e-9, "the outer wheel must also never exceed the configured full lock");
  assertTrue(result[0] > 0 && result[1] > 0, "both wheels must keep delta's own (positive) sign");
 }

 @Test void fullLockClampPreventsASignFlipOnATightGeometryBreakdown() {
  // Wroosbit review (comment 54164), reproduction 2: wheelbase L=1, half-track x=1, full-lock
  // delta=60 degrees - R=0.577 is now SMALLER than the half-track, so the raw denominator
  // (R-x) crosses zero and the unclamped atan formula returns -67.09 degrees for a POSITIVE
  // delta input, reversing the vehicle's own configured steer sign entirely. The clamp must
  // catch this sign flip (not just an over-large magnitude) and saturate at +60 degrees instead.
  // A single non-steering pivot wheel at z=-0.5 makes this a valid conventional two-axle layout
  // (both turnsWithSteer wheels must share the one steering band - two wheels at two DIFFERENT
  // z values, both marked turnsWithSteer, would itself be the "multi-axle steer" ambiguous
  // fallback, not this geometry-breakdown case).
  double[] x = {1.0, -1.0, 0.0};
  double[] z = {0.5, 0.5, -0.5}; // wheelbase L = 0.5 - (-0.5) = 1.0
  boolean[] steer = {true, true, false};
  double delta = Math.toRadians(60);
  double maxLock = Math.toRadians(60);
  double[] result = AckermannSteeringDistribution.steerRadians(x, z, steer, false, delta, maxLock);
  assertNotNull(result);
  assertTrue(result[0] > 0, "a positive delta must never flip the inner wheel's sign negative, got " + Math.toDegrees(result[0]) + " degrees");
  assertEquals(maxLock, result[0], 1e-9, "the geometry-breakdown wheel must saturate at the configured full lock in delta's own direction");
 }

 @Test void fullLockClampMirrorsCorrectlyOnNegativeDelta() {
  // Same breakdown geometry as the sign-flip case above, but steering the opposite way - the
  // clamp must saturate at -maxLock (not +maxLock), preserving the negative sign.
  double[] x = {1.0, -1.0, 0.0};
  double[] z = {0.5, 0.5, -0.5};
  boolean[] steer = {true, true, false};
  double delta = Math.toRadians(-60);
  double maxLock = Math.toRadians(60);
  double[] result = AckermannSteeringDistribution.steerRadians(x, z, steer, false, delta, maxLock);
  assertNotNull(result);
  assertTrue(result[1] < 0, "a negative delta must never flip the inner wheel's sign positive, got " + Math.toDegrees(result[1]) + " degrees");
  assertEquals(-maxLock, result[1], 1e-9, "the geometry-breakdown wheel must saturate at the negative configured full lock");
 }

 @Test void ordinarySteeringNeverTriggersTheFullLockClamp() {
  // Sanity check that the clamp introduced for the two cases above is inert for the everyday
  // 20-degree-class geometry every other test in this file already exercises.
  double delta = Math.toRadians(20);
  double[] clamped = AckermannSteeringDistribution.steerRadians(X, Z, FRONT_STEERS, false, delta, GENEROUS_LOCK);
  double[] unclampedEquivalent = AckermannSteeringDistribution.steerRadians(X, Z, FRONT_STEERS, false, delta, Math.toRadians(89));
  assertArrayEquals(unclampedEquivalent, clamped, 0.0, "an ordinary steering angle must be identical regardless of how generous the lock ceiling is");
 }

 /** The turn-center position implied by one wheel's own resulting angle, re-derived from the
  * same {@code x + L / tan(angle)} relation {@link AckermannSteeringDistribution} itself solves
  * - John's requested invariant (comment 54173): both wheels sharing a SINGLE common turning
  * center, never two independently-clipped angles that happen to each satisfy sign/bound but
  * imply two DIFFERENT turn centers. */
 private static double impliedTurnCenter(double angle, double x, double l) { return x + l / Math.tan(angle); }

 @Test void bothWheelsShareOneCommonTurningCenterAtOrdinaryLockInBothDirections() {
  // Sanity baseline: the invariant must hold even when the clamp never fires (both wheels
  // genuinely derived from the same original R).
  for (double sign : new double[]{1.0, -1.0}) {
   double delta = sign * Math.toRadians(20);
   double[] result = AckermannSteeringDistribution.steerRadians(X, Z, FRONT_STEERS, false, delta, GENEROUS_LOCK);
   assertNotNull(result);
   double l = 1.5 - (-1.5); // steer z (1.5) minus pivot z (-1.5), matching X/Z's own fixture
   assertEquals(impliedTurnCenter(result[0], X[0], l), impliedTurnCenter(result[1], X[1], l), 1e-9,
     "both steering wheels must imply the exact same turn-center position, sign=" + sign);
  }
 }

 @Test void bothWheelsShareOneCommonTurningCenterAtTheFullLockClampInBothDirections() {
  // John's requested invariant, at the SAME extreme geometry as the sign-flip reproduction
  // above, in both steering directions: the clamp must relax a SINGLE shared curvature, not
  // clip each wheel's angle independently against two different implied centers.
  // Same "needs an explicit non-steering pivot wheel" fixture shape as the sign-flip
  // reproduction above - two turnsWithSteer wheels at two different z values would itself be
  // the ambiguous multi-axle-steer fallback, not this geometry-breakdown case.
  double[] x = {1.0, -1.0, 0.0};
  double[] z = {0.5, 0.5, -0.5}; // wheelbase L = 1.0
  boolean[] steer = {true, true, false};
  double maxLock = Math.toRadians(60);
  for (double sign : new double[]{1.0, -1.0}) {
   double delta = sign * Math.toRadians(60);
   double[] result = AckermannSteeringDistribution.steerRadians(x, z, steer, false, delta, maxLock);
   assertNotNull(result);
   assertEquals(impliedTurnCenter(result[0], x[0], 1.0), impliedTurnCenter(result[1], x[1], 1.0), 1e-9,
     "the full-lock-clamped wheels must still imply the exact same (relaxed) turn-center position, sign=" + sign);
   // Both steering angles must also still individually respect sign/bound - the invariant above
   // does not by itself rule out a shared-but-wrong-signed or shared-but-oversized pair.
   for (int i = 0; i < 2; i++) {
    assertTrue(Math.signum(result[i]) == sign, "every steering wheel must keep delta's own sign, sign=" + sign);
    assertTrue(Math.abs(result[i]) <= maxLock + 1e-9, "every steering wheel must still respect the configured full lock, sign=" + sign);
   }
  }
 }
}
