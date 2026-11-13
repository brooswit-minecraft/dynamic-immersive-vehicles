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
 @Test void angularDragIsZeroWhenNotSubmergedOrNotRotating() {
  assertEquals(0, Buoyancy.angularDrag(10, 0, 5));
  assertEquals(0, Buoyancy.angularDrag(0, 1, 5));
 }
 @Test void angularDragGrowsQuadraticallyWithAngularSpeed() {
  double at1 = Buoyancy.angularDrag(1, 1, 3), at2 = Buoyancy.angularDrag(2, 1, 3);
  assertEquals(4 * at1, at2, 1e-9);
 }
 @Test void angularDragScalesWithSubmergedFraction() {
  assertEquals(Buoyancy.angularDrag(2, 1, 3) / 2, Buoyancy.angularDrag(2, .5, 3), 1e-9);
 }
 @Test void zeroAngularDragCoefficientReproducesNoRotationalDampingAtAll() {
  // The documented fallback (MINECRAFT-518): an unconfigured vehicle must get exactly zero
  // rotational damping from this term, regardless of how fast it is spinning or how submerged
  // it is - preserving the exact prior behavior (righting torque only as a per-float side
  // effect, nothing damping rotation itself).
  assertEquals(0, Buoyancy.angularDrag(50, 1, 0));
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
 @Test void linkedActiveTurningPropellerWithNonzeroDriveIsOperational() {
  assertTrue(Buoyancy.isPropellerOperational(true, true, 1200, 1));
  assertTrue(Buoyancy.isPropellerOperational(true, true, -1200, -1));
 }
 @Test void activeButUnlinkedPropellerIsNotOperationalRegardlessOfRpm() {
  // No engine actually drives this propeller - it must not count as operational even if its
  // own RPM reading happens to be nonzero (e.g. windmilling) and it is not destroyed/removed.
  assertFalse(Buoyancy.isPropellerOperational(false, true, 1200, 1));
  assertFalse(Buoyancy.isPropellerOperational(false, true, 0, 0));
 }
 @Test void linkedButStalledPropellerIsNotOperational() {
  assertFalse(Buoyancy.isPropellerOperational(true, true, 0, 1));
 }
 @Test void linkedInactivePropellerIsNotOperational() {
  assertFalse(Buoyancy.isPropellerOperational(true, false, 1200, 1));
 }
 @Test void linkedActiveTurningButZeroDrivePropellerIsNotOperational() {
  // Review finding: a linked, active, spinning propeller with zero computed pitch/direction
  // (feathered, or caught mid-swing through dynamic pitch) produces no thrust of its own
  // (signedPropellerDrive is 0) but must not count toward the shared budget's denominator
  // either - counting it would still dilute the share a propeller that IS actually thrusting
  // gets, just because this one happens to be spinning.
  assertFalse(Buoyancy.isPropellerOperational(true, true, 1200, 0));
 }
 @Test void mixedOperationalAndUnlinkedPropellerSetGivesFullAllocationToTheOperationalOneAndZeroToTheOther() {
  // Exercises the actual selection predicate BuoyantForces applies, not a hand-picked
  // operational count: propeller A is linked/active/turning; propeller B is active (not
  // destroyed/removed) but unlinked from any running engine - the exact "active but not
  // actually driven" case review's prior finding was about. B must not be counted in the
  // denominator, and A must receive the full, undiluted budget.
  boolean aOperational = Buoyancy.isPropellerOperational(true, true, 1200, 1);
  boolean bOperational = Buoyancy.isPropellerOperational(false, true, 0, 0);
  assertTrue(aOperational);
  assertFalse(bOperational);
  int operationalCount = (aOperational ? 1 : 0) + (bOperational ? 1 : 0);
  double share = Buoyancy.perOperationalPropellerForce(6000, operationalCount);
  assertEquals(6000, Buoyancy.propellerThrust(1, share, true, aOperational), "the operational propeller gets the full, undiluted budget");
  assertEquals(0, Buoyancy.propellerThrust(1, share, true, bOperational), "the unlinked propeller still produces zero");
 }
 @Test void twoOperationalPropellersSplitTheBudgetEvenly() {
  boolean aOperational = Buoyancy.isPropellerOperational(true, true, 1200, 1);
  boolean bOperational = Buoyancy.isPropellerOperational(true, true, -800, -1);
  int operationalCount = (aOperational ? 1 : 0) + (bOperational ? 1 : 0);
  double share = Buoyancy.perOperationalPropellerForce(6000, operationalCount);
  assertEquals(3000, share);
  assertEquals(3000, Buoyancy.propellerThrust(1, share, true, aOperational));
 }
 @Test void mixedZeroDriveAndNonzeroDrivePropellerSetGivesFullAllocationToTheThrustingOneAndZeroToTheOther() {
  // The exact mixed regression review asked for: one linked/active/turning propeller with zero
  // desired velocity (feathered - drive 0) plus one linked/active/turning propeller with
  // nonzero desired velocity (drive 1) - the latter must get the full, undiluted budget, not
  // half of it just because the feathered one is also spinning.
  double zeroDrive = Buoyancy.signedPropellerDrive(0, 1);
  double nonzeroDrive = Buoyancy.signedPropellerDrive(10, 1);
  boolean feathered = Buoyancy.isPropellerOperational(true, true, 1200, zeroDrive);
  boolean thrusting = Buoyancy.isPropellerOperational(true, true, 1200, nonzeroDrive);
  assertFalse(feathered, "zero drive must not count as operational despite spinning");
  assertTrue(thrusting);
  int operationalCount = (feathered ? 1 : 0) + (thrusting ? 1 : 0);
  double share = Buoyancy.perOperationalPropellerForce(6000, operationalCount);
  assertEquals(6000, Buoyancy.propellerThrust(nonzeroDrive, share, true, thrusting), "the thrusting propeller gets the full, undiluted budget");
  assertEquals(0, Buoyancy.propellerThrust(zeroDrive, share, true, feathered), "the feathered propeller still produces zero");
 }
 @Test void forwardPitchPropellerDrivesForwardRegardlessOfGear() {
  // A positive desiredLinearVelocity (IV's own computed thrust direction, driven by
  // currentPitch) must drive forward even if the review's prior, wrong source of direction
  // (transmission gear) would have said otherwise - this production seam never consults gear
  // at all, so there is nothing to disagree with.
  assertEquals(1, Buoyancy.signedPropellerDrive(12.5, 1));
  assertEquals(.5, Buoyancy.signedPropellerDrive(12.5, .5));
 }
 @Test void reversedPitchPropellerDrivesInReverseAtFullThrottle() {
  // A dynamic-pitch propeller swung negative by vehicle.reverseThrustVar - the exact scenario
  // review named, where the transmission can still read a forward gear - must drive in
  // reverse. Direction comes only from the propeller's own computed velocity.
  assertEquals(-1, Buoyancy.signedPropellerDrive(-8, 1));
 }
 @Test void engineInNeutralStillDrivesAPropellerWithNonzeroPitch() {
  // The review's other named case: an engine in neutral (gear sign 0) must not zero out a
  // propeller that is still actually turning and pitched for thrust - gear is never consulted.
  assertEquals(1, Buoyancy.signedPropellerDrive(5, 1));
 }
 @Test void zeroDesiredVelocityYieldsZeroDrive() {
  assertEquals(0, Buoyancy.signedPropellerDrive(0, 1));
 }
}
