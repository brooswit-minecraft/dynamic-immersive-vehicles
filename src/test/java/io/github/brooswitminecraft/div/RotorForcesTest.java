package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RotorForcesTest {
 @Test void collectiveIsZeroBelowTheDeadband() {
  // Matches IV's own PartPropeller rotor pitch mapping (throttle*1.35-0.35): a rotor needs a
  // minimum throttle before it has any collective pitch/lift authority at all.
  assertEquals(0, RotorForces.collective(0));
  assertEquals(0, RotorForces.collective(0.2));
 }

 @Test void collectiveReachesFullAuthorityAtFullThrottle() {
  assertEquals(1, RotorForces.collective(1), 1e-9);
 }

 @Test void collectiveIsClampedNotNegativeOrOverOne() {
  assertEquals(0, RotorForces.collective(-5));
  assertEquals(1, RotorForces.collective(5));
 }

 @Test void liftIsZeroAtZeroCollective() {
  assertEquals(0, RotorForces.lift(0,10000));
 }

 @Test void liftScalesLinearlyWithCollective() {
  assertEquals(5000, RotorForces.lift(0.5,10000), 1e-9);
 }

 @Test void cyclicTiltPassesThroughWithinRange() {
  double fiveDegrees = Math.toRadians(5);
  assertEquals(fiveDegrees, RotorForces.cyclicTilt(fiveDegrees), 1e-9);
 }

 @Test void cyclicTiltClampsAtTwentyDegrees() {
  double max = Math.toRadians(20);
  assertEquals(max, RotorForces.cyclicTilt(Math.toRadians(45)), 1e-9);
  assertEquals(-max, RotorForces.cyclicTilt(Math.toRadians(-45)), 1e-9);
 }

 @Test void verticalComponentIsFullThrustAtZeroTilt() {
  assertEquals(1000, RotorForces.verticalComponent(1000,0), 1e-9);
 }

 @Test void verticalComponentShrinksAsTiltGrows() {
  double level = RotorForces.verticalComponent(1000,0);
  double tilted = RotorForces.verticalComponent(1000,Math.toRadians(20));
  assertTrue(tilted < level, "tilting the disk to move must cost some vertical lift - the hover-to-forward-flight transition");
 }

 @Test void horizontalComponentIsZeroAtZeroTilt() {
  assertEquals(0, RotorForces.horizontalComponent(1000,0));
 }

 @Test void horizontalComponentFlipsSignWithTiltDirection() {
  double forward = RotorForces.horizontalComponent(1000,Math.toRadians(10));
  double backward = RotorForces.horizontalComponent(1000,Math.toRadians(-10));
  assertEquals(-forward, backward, 1e-9);
  assertTrue(forward > 0);
 }

 @Test void cyclicTorqueIsZeroAtZeroTilt() {
  assertEquals(0, RotorForces.cyclicTorque(0,5000,2));
 }

 @Test void cyclicTorqueScalesWithThrustNotAirspeed() {
  // Unlike a fixed-wing control surface, this must not depend on any dynamic-pressure/airspeed
  // term - only on available thrust and the tilt angle, so a hovering helicopter (zero airspeed)
  // still has full attitude authority.
  double low = RotorForces.cyclicTorque(Math.toRadians(10),2000,2);
  double high = RotorForces.cyclicTorque(Math.toRadians(10),8000,2);
  assertTrue(high > low);
  assertEquals(4*low, high, 1e-6);
 }

 @Test void cyclicTorqueFlipsSignWithTiltDirection() {
  double thrust = 5000, radius = 2;
  assertEquals(-RotorForces.cyclicTorque(Math.toRadians(10),thrust,radius),
    RotorForces.cyclicTorque(Math.toRadians(-10),thrust,radius), 1e-9);
 }

 @Test void yawTorqueIsZeroAtZeroRudderInput() {
  assertEquals(0, RotorForces.yawTorque(0,5000,2));
 }

 @Test void yawTorqueScalesWithThrustNotAirspeed() {
  double low = RotorForces.yawTorque(Math.toRadians(10),2000,2);
  double high = RotorForces.yawTorque(Math.toRadians(10),8000,2);
  assertEquals(4*low, high, 1e-6);
 }

 @Test void yawTorqueFlipsSignWithRudderDirection() {
  assertEquals(-RotorForces.yawTorque(Math.toRadians(10),5000,2),
    RotorForces.yawTorque(Math.toRadians(-10),5000,2), 1e-9);
 }

 @Test void rotorRadiusConvertsInchDiameterToMeterRadius() {
  // A representative 12 m main rotor (about 472 inches): diameter/2 in meters.
  double diameterInches = 472.44;
  double expectedRadiusMeters = diameterInches * 0.0254 / 2;
  assertEquals(expectedRadiusMeters, RotorForces.rotorRadiusMeters(diameterInches), 1e-9);
 }

 @Test void zeroDiameterGivesZeroRadius() {
  assertEquals(0, RotorForces.rotorRadiusMeters(0));
 }

 // MINECRAFT-522: configurable cyclic-limit and cyclic/yaw authority tuning.

 @Test void cyclicTiltWithExplicitLimitClampsAtThatLimitNotTwentyDegrees() {
  double tenDegreeLimit = Math.toRadians(10);
  assertEquals(tenDegreeLimit, RotorForces.cyclicTilt(Math.toRadians(45), tenDegreeLimit), 1e-9);
  assertEquals(-tenDegreeLimit, RotorForces.cyclicTilt(Math.toRadians(-45), tenDegreeLimit), 1e-9);
  double fiveDegrees = Math.toRadians(5);
  assertEquals(fiveDegrees, RotorForces.cyclicTilt(fiveDegrees, tenDegreeLimit), 1e-9, "within the configured limit, passes through unchanged");
 }

 @Test void cyclicTiltWithNoExplicitLimitMatchesTheTwentyDegreeDefault() {
  double input = Math.toRadians(30);
  assertEquals(RotorForces.cyclicTilt(input), RotorForces.cyclicTilt(input, Math.toRadians(20)), 1e-9);
 }

 @Test void cyclicTorqueWithUnitScaleMatchesTheUnscaledOverload() {
  double tilt = Math.toRadians(10), thrust = 5000, radius = 2;
  assertEquals(RotorForces.cyclicTorque(tilt, thrust, radius), RotorForces.cyclicTorque(tilt, thrust, radius, 1.0), 1e-9);
 }

 @Test void cyclicTorqueScalesLinearlyWithAuthorityScale() {
  double tilt = Math.toRadians(10), thrust = 5000, radius = 2;
  double base = RotorForces.cyclicTorque(tilt, thrust, radius, 1.0);
  assertEquals(2 * base, RotorForces.cyclicTorque(tilt, thrust, radius, 2.0), 1e-6);
  assertEquals(0.5 * base, RotorForces.cyclicTorque(tilt, thrust, radius, 0.5), 1e-6);
 }

 @Test void cyclicTorqueScaleGrantsNoUnpoweredFreeTorqueAtZeroThrust() {
  assertEquals(0, RotorForces.cyclicTorque(Math.toRadians(10), 0, 2, 5.0), 1e-9);
 }

 @Test void yawTorqueWithUnitScaleMatchesTheUnscaledOverload() {
  double rudder = Math.toRadians(10), thrust = 5000, tailArm = 2;
  assertEquals(RotorForces.yawTorque(rudder, thrust, tailArm), RotorForces.yawTorque(rudder, thrust, tailArm, 1.0), 1e-9);
 }

 @Test void yawTorqueScalesLinearlyWithAuthorityScale() {
  double rudder = Math.toRadians(10), thrust = 5000, tailArm = 2;
  double base = RotorForces.yawTorque(rudder, thrust, tailArm, 1.0);
  assertEquals(3 * base, RotorForces.yawTorque(rudder, thrust, tailArm, 3.0), 1e-6);
 }

 @Test void yawTorqueScaleGrantsNoUnpoweredFreeTorqueAtZeroThrust() {
  assertEquals(0, RotorForces.yawTorque(Math.toRadians(10), 0, 2, 5.0), 1e-9);
 }

 @Test void cyclicLimitDegreesIsAPerAxisClampNotACombinedDiskAngleBound() {
  // John's review (comment 53751): cyclicLimitDegrees clamps pitch and roll INDEPENDENTLY - same
  // as this class's own prior fixed 20-degree constant always did (PhysicsBridge.stepRotor calls
  // cyclicTilt separately for tiltPitch and tiltRoll) - so the COMBINED disk tilt
  // (hypot of both axes) can exceed the configured/default number when both axes are near their
  // own limit at once. This is unchanged, original behavior, not something this ticket introduces.
  double limit = Math.toRadians(45);
  double pitch = RotorForces.cyclicTilt(Math.toRadians(90), limit);
  double roll = RotorForces.cyclicTilt(Math.toRadians(90), limit);
  assertEquals(limit, pitch, 1e-9); assertEquals(limit, roll, 1e-9);
  double combined = Math.hypot(pitch, roll);
  assertTrue(combined > limit, "combined (hypot) disk tilt must be allowed to exceed the per-axis limit - it is not a total-disk-angle bound");
  assertEquals(Math.toRadians(45 * Math.sqrt(2)), combined, 1e-9);
 }
}
