package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FlightForcesTest {
 @Test void dynamicPressureScalesWithVelocitySquared() {
  assertEquals(0.5*1.225*100, FlightForces.dynamicPressure(1.225,10), 1e-9);
 }

 @Test void zeroAngleOfAttackHasNoLift() {
  assertEquals(0, FlightForces.liftCoefficient(0));
 }

 @Test void liftCoefficientGrowsLinearlyBeforeStall() {
  double shallow = FlightForces.liftCoefficient(Math.toRadians(5));
  double steeper = FlightForces.liftCoefficient(Math.toRadians(10));
  assertTrue(steeper > shallow);
  assertEquals(steeper, 2*shallow, 1e-9, "linear region: doubling the angle doubles the coefficient");
 }

 @Test void liftCoefficientIsClampedBeyondStall() {
  double atStall = FlightForces.liftCoefficient(Math.toRadians(15));
  double wayPastStall = FlightForces.liftCoefficient(Math.toRadians(80));
  assertEquals(atStall, wayPastStall, 1e-9, "a hard stall cutoff, not a growing coefficient past the stall angle");
 }

 @Test void liftCoefficientIsAntisymmetric() {
  assertEquals(-FlightForces.liftCoefficient(Math.toRadians(7)), FlightForces.liftCoefficient(Math.toRadians(-7)), 1e-9);
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
  assertEquals(0, FlightForces.controlSurfaceForce(0,5,50));
 }

 @Test void controlSurfaceForceFlipsSignWithDeflection() {
  assertEquals(-FlightForces.controlSurfaceForce(0.1,5,50), FlightForces.controlSurfaceForce(-0.1,5,50), 1e-9);
 }

 @Test void thrustIsZeroAtZeroThrottle() {
  assertEquals(0, FlightForces.thrust(0,10000));
 }

 @Test void thrustScalesLinearlyWithThrottle() {
  assertEquals(5000, FlightForces.thrust(0.5,10000), 1e-9);
 }

 @Test void unsetGravityFactorMeansFullyNeutralBuoyancy() {
  // The reviewed-and-fixed defect: buoyancy must be mass/gravity-compatible, not tied to IV's
  // literal ballastVolume (normally emptyMass/1000, not an SI volume). A representative 1,000 kg
  // blimp with the unset (0) gravityFactor default must get its full weight back as lift.
  double mass = 1000, gravity = 9.81;
  assertEquals(mass*gravity, FlightForces.buoyancy(mass,gravity,0), 1e-9);
 }

 @Test void fullGravityFactorMeansNoBuoyantLift() {
  assertEquals(0, FlightForces.buoyancy(1000,9.81,1), 1e-9);
 }

 @Test void partialGravityFactorSuppressesOnlyThatFraction() {
  double mass = 1000, gravity = 9.81, gravityFactor = 0.25;
  assertEquals(mass*gravity*0.75, FlightForces.buoyancy(mass,gravity,gravityFactor), 1e-9);
 }

 @Test void representativeBlimpNetsNearZeroVerticalForceAtNeutralBallast() {
  // The integration-level seam the review asked for: a representative mass/ballast combination
  // (1,000 kg blimp, unset gravityFactor, centered ballast control) must leave buoyancy within a
  // small trim band of Sable's own gravity pull on that mass - not off by three orders of
  // magnitude the way the old rho*V*g-on-ballastVolume calculation was.
  double mass = 1000, gravity = 9.81;
  double buoyant = FlightForces.buoyancy(mass,gravity,0) + FlightForces.ballastTrim(mass,gravity,0);
  double sableGravityPull = mass*gravity;
  assertEquals(sableGravityPull, buoyant, 1e-9, "centered ballast control: exactly neutral");
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
