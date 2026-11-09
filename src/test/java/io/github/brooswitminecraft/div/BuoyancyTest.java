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
  assertEquals(0, Buoyancy.propellerThrust(1, 5000, false, true));
  assertEquals(0, Buoyancy.propellerThrust(-1, 5000, false, true));
 }
 @Test void submergedPropellerThrustFollowsSignedThrottle() {
  assertEquals(5000, Buoyancy.propellerThrust(1, 5000, true, true));
  assertEquals(-2500, Buoyancy.propellerThrust(-.5, 5000, true, true));
 }
 @Test void inactivePropellerProducesNoThrustEvenWhenSubmergedAtFullThrottle() {
  // An inactive/damaged propeller must stay silent regardless of throttle or submersion -
  // the exact review finding this pins down.
  assertEquals(0, Buoyancy.propellerThrust(1, 5000, true, false));
  assertEquals(0, Buoyancy.propellerThrust(-1, 5000, true, false));
 }
 @Test void noOperationalPropellersGetsZeroForceShare() {
  assertEquals(0, Buoyancy.perOperationalPropellerForce(6000, 0));
 }
 @Test void soleOperationalPropellerGetsTheFullBudgetRegardlessOfOtherInstalledPropellers() {
  // A mixed set - one linked/turning propeller plus any number of unpowered/inactive/spare
  // ones - must not dilute the operational propeller's share: the force budget divides only
  // by how many propellers are actually operational this tick, not by how many exist on the
  // chassis. This is the exact "dilutes the configured thrust share" finding review flagged.
  assertEquals(6000, Buoyancy.perOperationalPropellerForce(6000, 1));
 }
 @Test void mixedPoweredAndUnpoweredPropellerSetGivesFullAllocationToThePoweredOneAndZeroToTheOther() {
  // End-to-end of the same scenario through propellerThrust: one operational propeller taking
  // the undiluted share, one non-operational propeller (present on the chassis, but not
  // counted in perOperationalPropellerForce's denominator) producing exactly zero.
  double operationalCount = 1; // the unpowered propeller is excluded from the count entirely
  double share = Buoyancy.perOperationalPropellerForce(6000, (int) operationalCount);
  assertEquals(6000, Buoyancy.propellerThrust(1, share, true, true), "the operational propeller gets the full, undiluted budget");
  assertEquals(0, Buoyancy.propellerThrust(1, share, true, false), "the unpowered propeller still produces zero regardless of the share size");
 }
 @Test void twoOperationalPropellersSplitTheBudgetEvenly() {
  double share = Buoyancy.perOperationalPropellerForce(6000, 2);
  assertEquals(3000, share);
  assertEquals(3000, Buoyancy.propellerThrust(1, share, true, true));
 }
}
