package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure force math for the buoyancy domain - no live entity/world/Sable runtime needed. */
class BuoyancyTest {
 @Test void fullySubmergedSpanIsOneHundredPercent() {
  assertEquals(1, Buoyancy.submergedFraction(0, 2, 10));
 }
 @Test void fullyAboveWaterIsZero() {
  assertEquals(0, Buoyancy.submergedFraction(5, 7, 0));
 }
 @Test void halfSubmergedSpanIsOneHalf() {
  assertEquals(.5, Buoyancy.submergedFraction(-1, 1, 0));
 }
 @Test void degenerateSpanIsNeverSubmerged() {
  assertEquals(0, Buoyancy.submergedFraction(1, 1, 5));
  assertEquals(0, Buoyancy.submergedFraction(2, 1, 5));
 }
 @Test void buoyantForceScalesWithFractionAndVolume() {
  assertEquals(0, Buoyancy.buoyantForce(0, 10));
  assertEquals(Buoyancy.WATER_DENSITY_KG_M3 * Buoyancy.GRAVITY * 2, Buoyancy.buoyantForce(1, 2), 1e-9);
  assertEquals(Buoyancy.buoyantForce(1, 2) / 2, Buoyancy.buoyantForce(.5, 2), 1e-9);
 }
 @Test void dragIsZeroWhenNotSubmergedOrNotMoving() {
  assertEquals(0, Buoyancy.drag(10, 0, 5));
  assertEquals(0, Buoyancy.drag(0, 1, 5));
 }
 @Test void dragGrowsQuadraticallyWithSpeed() {
  double at1 = Buoyancy.drag(1, 1, 3), at2 = Buoyancy.drag(2, 1, 3);
  assertEquals(4 * at1, at2, 1e-9);
 }
 @Test void propellerAboveWaterProducesNoThrustRegardlessOfThrottle() {
  assertEquals(0, Buoyancy.propellerThrust(1, 5000, false));
  assertEquals(0, Buoyancy.propellerThrust(-1, 5000, false));
 }
 @Test void submergedPropellerThrustFollowsSignedThrottle() {
  assertEquals(5000, Buoyancy.propellerThrust(1, 5000, true));
  assertEquals(-2500, Buoyancy.propellerThrust(-.5, 5000, true));
 }
}
