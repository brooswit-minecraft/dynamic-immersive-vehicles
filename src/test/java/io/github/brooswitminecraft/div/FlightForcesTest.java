package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FlightForcesTest {
 @Test void dynamicPressureScalesWithVelocitySquared() {
  assertEquals(0.5*1.225*100, FlightForces.dynamicPressure(1.225,10), 1e-9);
 }

 @Test void zeroAngleOfAttackHasNoLift() {
  assertEquals(0, FlightForces.liftCoefficient(0, FlightForces.DEFAULT_STALL_ANGLE_DEGREES, FlightForces.DEFAULT_LIFT_SLOPE));
 }

 @Test void liftCoefficientGrowsLinearlyBeforeStall() {
  double shallow = FlightForces.liftCoefficient(Math.toRadians(5), FlightForces.DEFAULT_STALL_ANGLE_DEGREES, FlightForces.DEFAULT_LIFT_SLOPE);
  double steeper = FlightForces.liftCoefficient(Math.toRadians(10), FlightForces.DEFAULT_STALL_ANGLE_DEGREES, FlightForces.DEFAULT_LIFT_SLOPE);
  assertTrue(steeper > shallow);
  assertEquals(steeper, 2*shallow, 1e-9, "linear region: doubling the angle doubles the coefficient");
 }

 @Test void liftCoefficientIsClampedBeyondStall() {
  double atStall = FlightForces.liftCoefficient(Math.toRadians(15), FlightForces.DEFAULT_STALL_ANGLE_DEGREES, FlightForces.DEFAULT_LIFT_SLOPE);
  double wayPastStall = FlightForces.liftCoefficient(Math.toRadians(80), FlightForces.DEFAULT_STALL_ANGLE_DEGREES, FlightForces.DEFAULT_LIFT_SLOPE);
  assertEquals(atStall, wayPastStall, 1e-9, "a hard stall cutoff, not a growing coefficient past the stall angle");
 }

 @Test void liftCoefficientIsAntisymmetric() {
  assertEquals(-FlightForces.liftCoefficient(Math.toRadians(7), FlightForces.DEFAULT_STALL_ANGLE_DEGREES, FlightForces.DEFAULT_LIFT_SLOPE),
    FlightForces.liftCoefficient(Math.toRadians(-7), FlightForces.DEFAULT_STALL_ANGLE_DEGREES, FlightForces.DEFAULT_LIFT_SLOPE), 1e-9);
 }

 // MINECRAFT-521: stallAngleDegrees/liftSlope are now caller-supplied (Overrides.Tuning fields),
 // not hardcoded here - a configured vehicle must actually see a different stall cap/slope.

 @Test void configuredStallAngleMovesWhereTheHardCutoffKicksIn() {
  double narrowStall = FlightForces.liftCoefficient(Math.toRadians(10), 5, FlightForces.DEFAULT_LIFT_SLOPE);
  double atFivedegrees = FlightForces.liftCoefficient(Math.toRadians(5), 5, FlightForces.DEFAULT_LIFT_SLOPE);
  assertEquals(atFivedegrees, narrowStall, 1e-9, "a 5deg stall cap clamps a 10deg angle of attack to its 5deg limit");
 }

 @Test void configuredLiftSlopeScalesTheLinearRegion() {
  double doubledSlope = FlightForces.liftCoefficient(Math.toRadians(5), FlightForces.DEFAULT_STALL_ANGLE_DEGREES, 2*FlightForces.DEFAULT_LIFT_SLOPE);
  double defaultSlope = FlightForces.liftCoefficient(Math.toRadians(5), FlightForces.DEFAULT_STALL_ANGLE_DEGREES, FlightForces.DEFAULT_LIFT_SLOPE);
  assertEquals(2*defaultSlope, doubledSlope, 1e-9, "doubling liftSlope must double the resulting coefficient at the same angle");
 }

 @Test void boundedExtremeStallAngleAndLiftSlopeStayFiniteAndClamped() {
  // Finite/bounded-extreme coverage (ticket's own ask): the max validated stallAngleDegrees (45)
  // and liftSlope (10) must still produce a finite, correctly-clamped result, never NaN/infinite.
  double atCap = FlightForces.liftCoefficient(Math.toRadians(45), 45, 10);
  double pastCap = FlightForces.liftCoefficient(Math.toRadians(90), 45, 10);
  assertTrue(Double.isFinite(atCap) && Double.isFinite(pastCap));
  assertEquals(atCap, pastCap, 1e-9, "still a hard cutoff at the extreme end of the validated range");
 }

 @Test void noWingMeansNoInducedDrag() {
  assertEquals(0.03, FlightForces.dragCoefficient(1.2,0.03,0), 1e-9);
 }

 @Test void higherLiftIncreasesInducedDragWithAWing() {
  double low = FlightForces.dragCoefficient(0.2,0.03,8);
  double high = FlightForces.dragCoefficient(1.0,0.03,8);
  assertTrue(high > low);
 }

 @Test void aeroForceScalesWithCoefficientAreaAndPressure() {
  assertEquals(1.2*20*50, FlightForces.aeroForce(1.2,20,50), 1e-9);
 }

 @Test void controlSurfaceForceIsZeroAtZeroDeflection() {
  assertEquals(0, FlightForces.controlSurfaceForce(0,5,50,FlightForces.DEFAULT_CONTROL_SURFACE_SLOPE));
 }

 @Test void controlSurfaceForceFlipsSignWithDeflection() {
  assertEquals(-FlightForces.controlSurfaceForce(0.1,5,50,FlightForces.DEFAULT_CONTROL_SURFACE_SLOPE),
    FlightForces.controlSurfaceForce(-0.1,5,50,FlightForces.DEFAULT_CONTROL_SURFACE_SLOPE), 1e-9);
 }

 // MINECRAFT-521: controlSurfaceSlope is now independently tunable from the wing's own liftSlope.

 @Test void configuredControlSurfaceSlopeScalesAuthorityIndependentlyOfLiftSlope() {
  double doubledAuthority = FlightForces.controlSurfaceForce(0.1,5,50,2*FlightForces.DEFAULT_CONTROL_SURFACE_SLOPE);
  double defaultAuthority = FlightForces.controlSurfaceForce(0.1,5,50,FlightForces.DEFAULT_CONTROL_SURFACE_SLOPE);
  assertEquals(2*defaultAuthority, doubledAuthority, 1e-9, "doubling controlSurfaceSlope must double control-surface force");
 }

 @Test void boundedExtremeControlSurfaceSlopeStaysFinite() {
  double atCap = FlightForces.controlSurfaceForce(0.1,5,50,10);
  assertTrue(Double.isFinite(atCap) && atCap > 0);
 }

 @Test void thrustIsZeroAtZeroThrottle() {
  assertEquals(0, FlightForces.thrust(0,10000));
 }

 @Test void thrustScalesLinearlyWithThrottle() {
  assertEquals(5000, FlightForces.thrust(0.5,10000), 1e-9);
 }

 @Test void ballastVolumeUnconfiguredMeansNoBuoyancyAtAll() {
  // Review finding (round 2): gravity suppression in pinned IV source is keyed to whether
  // ballastVolume is configured/active (ballastVolumeVar > 0), not to gravityFactor - whose own
  // runtime default actually resolves to full gravity (1) when a pack leaves it unset. Seam case
  // (a) from review: an isBlimp vehicle with ballastVolume=0 must get ordinary gravity, i.e. no
  // buoyant force from this method at all, regardless of ballastControl.
  assertEquals(0, FlightForces.blimpBuoyancy(1000,9.81,0,0));
  assertEquals(0, FlightForces.blimpBuoyancy(1000,9.81,0,1));
 }

 @Test void ballastVolumeActiveGivesFullNeutralBuoyancyRegardlessOfGravityFactor() {
  // Seam case (b) from review: ballastVolume>0 (ballast active) must net full weight back as
  // lift (centered ballast control), independent of any gravityFactor value - this method takes
  // no gravityFactor parameter at all, so there is nothing for a pack's explicit gravityFactor=1
  // (or any other value) to wrongly suppress; IV's own gravity suppression doesn't key off it
  // either, per the pinned source the review cited.
  double mass = 1000, gravity = 9.81;
  assertEquals(mass*gravity, FlightForces.blimpBuoyancy(mass,gravity,1,0), 1e-9);
  // IV's documented ballastVolume magnitude (emptyMass/1000) is just "nonzero" to this method -
  // the literal number must not matter, only activation.
  assertEquals(mass*gravity, FlightForces.blimpBuoyancy(mass,gravity,1.0,0), 1e-9);
 }

 @Test void representativeBlimpNetsNearZeroVerticalForceAtNeutralBallast() {
  // The integration-level seam the first review round asked for: a representative mass/ballast
  // combination (1,000 kg blimp, active ballastVolume, centered ballast control) must leave
  // buoyancy exactly matching Sable's own gravity pull on that mass - not off by three orders of
  // magnitude the way the original rho*V*g-on-ballastVolume calculation was.
  double mass = 1000, gravity = 9.81;
  double buoyant = FlightForces.blimpBuoyancy(mass,gravity,1,0);
  double sableGravityPull = mass*gravity;
  assertEquals(sableGravityPull, buoyant, 1e-9, "centered ballast control: exactly neutral");
 }

 @Test void blimpBuoyancyIncludesBallastTrim() {
  double mass = 1000, gravity = 9.81;
  assertEquals(mass*gravity + mass*gravity*0.1, FlightForces.blimpBuoyancy(mass,gravity,1,1), 1e-9);
 }

 @Test void ballastTrimIsZeroAtCenteredControl() {
  assertEquals(0, FlightForces.ballastTrim(1000,9.81,0));
 }

 @Test void ballastTrimIsASmallFractionOfWeightAtFullControl() {
  double mass = 1000, gravity = 9.81;
  double fullRelease = FlightForces.ballastTrim(mass,gravity,1);
  assertEquals(mass*gravity*0.1, fullRelease, 1e-9);
  assertTrue(fullRelease < mass*gravity, "trim must stay a fraction of weight, not overwhelm it");
 }

 @Test void ballastTrimFlipsSignWithControlDirection() {
  assertEquals(-FlightForces.ballastTrim(1000,9.81,0.6), FlightForces.ballastTrim(1000,9.81,-0.6), 1e-9);
 }

 @Test void ballastTrimClampsBeyondFullControl() {
  assertEquals(FlightForces.ballastTrim(1000,9.81,1), FlightForces.ballastTrim(1000,9.81,5), 1e-9);
 }
}
