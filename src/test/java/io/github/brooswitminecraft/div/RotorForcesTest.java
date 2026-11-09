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
}
