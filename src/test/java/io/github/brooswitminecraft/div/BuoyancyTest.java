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
 // MINECRAFT-535: directional water drag - equal-axis parity against the existing isotropic
 // vector formula already in production (BuoyantForces.apply, before this ticket): force =
 // -dragMagnitude/speed * localVelocity, i.e. force_i = -fraction*dragCoefficient*speed*v_i.
 @Test void directionalDragWithUnitMultiplierMatchesIsotropicFormulaOnEveryAxis() {
  double speed = 5.0, fraction = 0.6, coefficient = 400;
  double vx = 3.0, vy = -1.5, vz = 4.0;
  double isotropicX = -fraction * coefficient * speed * vx;
  double isotropicY = -fraction * coefficient * speed * vy;
  double isotropicZ = -fraction * coefficient * speed * vz;
  assertEquals(isotropicX, Buoyancy.directionalDragForce(speed, vx, fraction, coefficient, 1.0), 1e-9);
  assertEquals(isotropicY, Buoyancy.directionalDragForce(speed, vy, fraction, coefficient, 1.0), 1e-9);
  assertEquals(isotropicZ, Buoyancy.directionalDragForce(speed, vz, fraction, coefficient, 1.0), 1e-9);
 }
 @Test void directionalDragScalesLinearlyWithItsOwnAxisMultiplier() {
  double base = Buoyancy.directionalDragForce(5, 2, 0.5, 100, 1.0);
  assertEquals(2 * base, Buoyancy.directionalDragForce(5, 2, 0.5, 100, 2.0), 1e-9);
  assertEquals(0, Buoyancy.directionalDragForce(5, 2, 0.5, 100, 0.0), 1e-9);
 }
 @Test void directionalDragAlwaysOpposesItsOwnAxisVelocityForAnyNonnegativeMultiplier() {
  // Dissipative-only requirement: a nonnegative multiplier must never flip the force to push
  // WITH motion (same sign as velocity) instead of against it (adding energy).
  for (double multiplier : new double[]{0, 0.1, 1, 2.5, 5}) {
   double forcePositiveVelocity = Buoyancy.directionalDragForce(5, 3, 0.8, 400, multiplier);
   double forceNegativeVelocity = Buoyancy.directionalDragForce(5, -3, 0.8, 400, multiplier);
   assertTrue(forcePositiveVelocity <= 0, "force must oppose positive velocity for multiplier=" + multiplier);
   assertTrue(forceNegativeVelocity >= 0, "force must oppose negative velocity for multiplier=" + multiplier);
  }
 }
 @Test void directionalDragIsZeroWhenNotSubmergedOrNotMovingOnThatAxis() {
  assertEquals(0, Buoyancy.directionalDragForce(5, 0, 0, 400, 1.0), 1e-9);
  assertEquals(0, Buoyancy.directionalDragForce(5, 3, 0, 400, 1.0), 1e-9);
  assertEquals(0, Buoyancy.directionalDragForce(5, 0, 1, 400, 1.0), 1e-9);
 }
 // MINECRAFT-545/547 (John's comment 54677: "seek a small production-used numerical regression
 // demonstrating any discrete drag overshoot"). Pins the EXACT numeric incident the authorized
 // M545-packet-df4b0c2 run's own retained log captured (gameTime=205 and 206, towed float 1 -
 // waterDrag=400 and mass=400kg are this fixture's own real configured values, DT=0.05s is
 // PhysicsBridge's own real constant) - using ONLY the real production directionalDragForce
 // function, not a reimplementation, applied here as a single-step explicit-Euler impulse
 // exactly like BuoyantForces.apply's own `handle.applyImpulseAtPoint(..., force.mul(DT))` call.
 // Overshoot = the single-step impulse-induced velocity change at this float's own point EXCEEDS
 // the local velocity component it was meant to cancel - not just reduce it, but overshoot past
 // zero and come out the other side, consistent with BuoyantForces' own already-documented review
 // finding (comment 53679) that an inertia-converted impulse CAN overshoot/reverse for a light
 // enough body and large enough coefficient - here demonstrated for the LINEAR per-float drag
 // term specifically (the angular/rotational dedicated term, separately gated by
 // tuning.angularWaterDrag, was confirmed OFF for this run and is not what this test exercises).
 @Test void directionalDragOvershootsAtTheExactReconnectDivergenceIncident() {
  double dt = 0.05, mass = 400, waterDrag = 400;
  // gameTime=206 (the step that actually diverged): towed float 1's own real local point-
  // velocity magnitude, computed from this run's own retained entry velocity/angular velocity
  // and worldArm (angular x arm + linearVelocity), submerged fraction 1.0 - see VERIFICATION.md
  // for the full cross-product derivation. Treating the full local-velocity magnitude as a
  // single axis with axisMultiplier=1.0 is exact here (every axis multiplier for this fixture's
  // hulls is the Overrides.FALLBACK default of 1.0, making the formula direction-independent).
  double explodingStepLocalSpeed = 43.90;
  double explodingStepForce = Buoyancy.directionalDragForce(explodingStepLocalSpeed, explodingStepLocalSpeed, 1.0, waterDrag, 1.0);
  double explodingStepVelocityChange = explodingStepForce * dt / mass;
  assertTrue(Math.abs(explodingStepVelocityChange) > explodingStepLocalSpeed,
    "the exploding step's own single-step impulse must overshoot past zero, not merely reduce the local velocity");

  // gameTime=205 (the immediately preceding step, which did NOT yet diverge): same formula, same
  // real coefficients, this earlier step's own smaller local-velocity magnitude and submerged
  // fraction (0.739...) - included as the direct contrast: the SAME formula does NOT overshoot
  // here, so overshoot is a property of the velocity magnitude reached, not an unconditional bug
  // in the formula itself.
  double stableStepLocalSpeed = 16.66, stableStepFraction = 0.7391548872657683;
  double stableStepForce = Buoyancy.directionalDragForce(stableStepLocalSpeed, stableStepLocalSpeed, stableStepFraction, waterDrag, 1.0);
  double stableStepVelocityChange = stableStepForce * dt / mass;
  assertTrue(Math.abs(stableStepVelocityChange) < stableStepLocalSpeed,
    "the immediately preceding, non-diverging step must NOT overshoot - the contrast confirms this is magnitude-dependent, not an unconditional defect");
 }
 // MINECRAFT-518 review (comment 53679): the retention fraction returned here must always be in
 // (0,1] regardless of how large angularSpeed/submergedFraction/angularDragCoefficient/dt are -
 // the whole point of the implicit (not explicit-impulse) formulation is that it is impossible to
 // overshoot past zero or reverse/amplify the hull's spin, with no inertia needed anywhere.

 @Test void angularDragRetentionIsOneWhenNotSubmergedOrNotRotatingOrZeroCoefficient() {
  // Retention 1 means "no change at all" - matches the exact prior behavior (no dedicated
  // rotational damping term) whenever any factor in the product is zero.
  assertEquals(1.0, Buoyancy.angularDragRetention(10, 0, 5, 0.05));
  assertEquals(1.0, Buoyancy.angularDragRetention(0, 1, 5, 0.05));
  assertEquals(1.0, Buoyancy.angularDragRetention(50, 1, 0, 0.05));
 }
 @Test void angularDragRetentionIsAlwaysInZeroToOneRangeAcrossWideInputs() {
  // The exact regime review's worked example exploited for the old explicit-impulse formula
  // (a large coefficient, small inertia) - here there is no inertia to be unaware of at all, so
  // even an extreme coefficient/speed/fraction/dt combination must stay bounded.
  for (double coefficient : new double[]{0, 1, 100, 100000, 1e9}) {
   for (double speed : new double[]{0, 1e-3, 1, 50, 1e6}) {
    for (double fraction : new double[]{0, 0.3, 1}) {
     double retention = Buoyancy.angularDragRetention(speed, fraction, coefficient, 0.05);
     assertTrue(retention > 0 && retention <= 1.0,
       "retention=" + retention + " out of (0,1] for coefficient=" + coefficient + " speed=" + speed + " fraction=" + fraction);
    }
   }
  }
 }
 @Test void angularDragRetentionNeverOvershootsOrReversesSpinEvenAtReviewsExactWorkedExample() {
  // Review's own numbers (comment 53679): coefficient=100000, fraction=1, speed=1 rad/s, at the
  // explicit formula's dt=0.05 - the OLD explicit-impulse formula (if it had access to an
  // inertia of 1000 kg*m^2) would have produced omega'=-4 (reversed and amplified). The NEW
  // retention-based approach needs no inertia at all and must still land in (0,1].
  double retention = Buoyancy.angularDragRetention(1, 1, 100000, 0.05);
  assertTrue(retention > 0 && retention <= 1.0, "retention=" + retention + " must stay in (0,1], never reversing or amplifying spin");
 }
 @Test void angularDragRetentionDecreasesAsCoefficientOrSpeedOrFractionGrows() {
  double baseline = Buoyancy.angularDragRetention(1, 1, 1, 0.05);
  assertTrue(Buoyancy.angularDragRetention(1, 1, 10, 0.05) < baseline, "a larger coefficient must damp more (smaller retention)");
  assertTrue(Buoyancy.angularDragRetention(5, 1, 1, 0.05) < baseline, "a larger angular speed must damp more");
  assertTrue(Buoyancy.angularDragRetention(1, 0.1, 1, 0.05) > baseline, "a smaller submerged fraction must damp less (larger retention)");
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
