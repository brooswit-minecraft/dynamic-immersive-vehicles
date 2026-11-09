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

 @Test void buoyancyIsZeroWithNoBallastVolume() {
  assertEquals(0, FlightForces.buoyancy(1.225,0));
 }

 @Test void buoyancyScalesWithDisplacedVolume() {
  assertEquals(1.225*100*9.81, FlightForces.buoyancy(1.225,100), 1e-9);
 }
}
