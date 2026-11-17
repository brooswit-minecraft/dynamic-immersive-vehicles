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
 // MINECRAFT-545/547 (John's comments 54677/54680: "seek a small production-used numerical
 // regression demonstrating any discrete drag overshoot" / "J/m is a COM translational
 // increment, NOT full point-velocity change for an off-center impulse"). Pins the EXACT numeric
 // incident the authorized M545-packet-df4b0c2 run's own retained log captured (gameTime=205 and
 // 206, towed float 1 - waterDrag=400 and mass=400kg are this fixture's own real configured
 // values, DT=0.05s is PhysicsBridge's own real constant) - using ONLY the real production
 // directionalDragForce function, not a reimplementation.
 // SCOPE, STATED EXPLICITLY (54680's own correction): `force*dt/mass` below is the body's own
 // COM TRANSLATIONAL velocity increment for this single float's own impulse - NOT the actual
 // velocity change AT that float's off-center point, which also requires the rotational response
 // (moment of inertia, unavailable via RigidBodyHandle - the same missing-inertia limitation
 // documented and worked around in angularDragRetention's own doc) and the OTHER float's own
 // simultaneous impulse. This assertion characterizes ONE scalar fact about the explicit drag
 // formula under these real numbers - that the COM-frame velocity correction it alone would
 // produce already exceeds the local velocity it is computed FROM - not a proven complete rigid-
 // body event, and not proof this is the first onset of divergence (growth was already present
 // one step earlier - see the second half of this test, and do not read its own lower margin as
 // "stable").
 @Test void directionalDragComOnlyVelocityIncrementExceedsLocalVelocityAtTheDivergingStep() {
  double dt = 0.05, mass = 400, waterDrag = 400;
  // gameTime=206 (the step during which the fixture's own observed divergence occurred): towed
  // float 1's own real local point-velocity magnitude, computed from this run's own retained
  // entry velocity/angular velocity and worldArm (angular x arm + linearVelocity), submerged
  // fraction 1.0 - see VERIFICATION.md for the full cross-product derivation. Treating the full
  // local-velocity magnitude as a single axis with axisMultiplier=1.0 is exact here (every axis
  // multiplier for this fixture's hulls is the Overrides.FALLBACK default of 1.0, making the
  // formula direction-independent).
  double divergingStepLocalSpeed = 43.90;
  double divergingStepForce = Buoyancy.directionalDragForce(divergingStepLocalSpeed, divergingStepLocalSpeed, 1.0, waterDrag, 1.0);
  double divergingStepComVelocityIncrement = divergingStepForce * dt / mass;
  assertTrue(Math.abs(divergingStepComVelocityIncrement) > divergingStepLocalSpeed,
    "this single float's own COM-frame velocity increment (force*dt/mass) must exceed the local point-velocity it was computed from");

  // gameTime=205 (the immediately preceding step): same formula, same real coefficients, this
  // earlier step's own smaller local-velocity magnitude and submerged fraction (0.739...).
  // CORRECTED (54680): do NOT call this step "stable" - the body's own angular velocity was
  // ALREADY growing between the entry readings at this step and the next (confirmed separately
  // in VERIFICATION.md's own per-tick table), so this earlier moment is not categorically safe,
  // only BELOW this one particular COM-only threshold at this one instant. The only claim this
  // half of the test supports is the narrower one below.
  double earlierStepLocalSpeed = 16.66, earlierStepFraction = 0.7391548872657683;
  double earlierStepForce = Buoyancy.directionalDragForce(earlierStepLocalSpeed, earlierStepLocalSpeed, earlierStepFraction, waterDrag, 1.0);
  double earlierStepComVelocityIncrement = earlierStepForce * dt / mass;
  assertTrue(Math.abs(earlierStepComVelocityIncrement) < earlierStepLocalSpeed,
    "this earlier step's own COM-frame velocity increment does not exceed its own local point-velocity - narrower than 'stable', just below this one threshold at this one instant");
 }
 // MINECRAFT-545/547 (John's comment 54680): the production-relevant dissipativity criterion -
 // classical explicit-Euler linear stability (margin < 2.0), linearizing the quadratic drag
 // around the current speed, same real values as the test above.
 @Test void explicitDragStabilityMarginExceedsTwoAtTheDivergingStepButNotTheEarlierOne() {
  double dt = 0.05, mass = 400, waterDrag = 400;
  double divergingMargin = Buoyancy.explicitDragStabilityMargin(43.90, 1.0, waterDrag, 1.0, dt, mass);
  double earlierMargin = Buoyancy.explicitDragStabilityMargin(16.66, 0.7391548872657683, waterDrag, 1.0, dt, mass);
  assertTrue(divergingMargin >= 2.0, "margin=" + divergingMargin + " must be at/past the explicit-stability threshold at the diverging step");
  assertTrue(earlierMargin < 2.0, "margin=" + earlierMargin + " must be under the threshold at the earlier step");
  assertFalse(Buoyancy.isExplicitDragStepStable(43.90, 1.0, waterDrag, 1.0, dt, mass));
  assertTrue(Buoyancy.isExplicitDragStepStable(16.66, 0.7391548872657683, waterDrag, 1.0, dt, mass));
 }
 @Test void explicitDragStabilityMarginIsZeroWhenDragIsConfiguredOff() {
  assertEquals(0, Buoyancy.explicitDragStabilityMargin(50, 1.0, 0, 1.0, 0.05, 400));
  assertTrue(Buoyancy.isExplicitDragStepStable(50, 1.0, 0, 1.0, 0.05, 400));
 }
 // MINECRAFT-545/547 (John's comments 54680/54684). directionalDragRetention is a PROPOSED
 // alternative to the current explicit impulse - CORRECTED (54684) to divide by mass (a force
 // coefficient reused without mass-normalization was a real dimensional error, confirmed below)
 // and no longer claimed "exact" (the 1/(1+..) form is backward Euler for this method's own
 // linearized-speed ODE, not that ODE's true exact exponential solution - see the method's own
 // doc). These tests mirror angularDragRetention's own tests below in STRUCTURE, not in claiming
 // the same exactness or inertia-free completeness - this scalar form alone does not reproduce
 // the off-center torque coupling the current per-float impulse produces (54684's own point 3).
 @Test void directionalDragRetentionIsOneWhenNotSubmergedOrNotMovingOrZeroCoefficient() {
  assertEquals(1.0, Buoyancy.directionalDragRetention(10, 0, 400, 1.0, 0.05, 400));
  assertEquals(1.0, Buoyancy.directionalDragRetention(0, 1, 400, 1.0, 0.05, 400));
  assertEquals(1.0, Buoyancy.directionalDragRetention(50, 1, 0, 1.0, 0.05, 400));
 }
 @Test void directionalDragRetentionMatchesTheMassConsistentValueAtTheExactIncidentNumbers() {
  // MINECRAFT-545/547 (John's comment 54684): pins the EXACT correction - the first version of
  // this method (missing mass) returned 1/879 at these real values; the mass-consistent version
  // must return 1/3.195, confirmed independently by John's own review.
  double retention = Buoyancy.directionalDragRetention(43.90, 1.0, 400, 1.0, 0.05, 400);
  assertEquals(1.0 / 3.195, retention, 1e-3);
 }
 @Test void directionalDragRetentionIsAlwaysInZeroToOneRangeAcrossWideInputsIncludingThisIncidentsOwnExtremeSpeed() {
  for (double coefficient : new double[]{0, 1, 400, 100000}) {
   for (double speed : new double[]{0, 1e-3, 1, 16.66, 43.90, 1e6}) {
    for (double fraction : new double[]{0, 0.3, 0.7391548872657683, 1}) {
     for (double mass : new double[]{1, 50, 400, 1e6}) {
      double retention = Buoyancy.directionalDragRetention(speed, fraction, coefficient, 1.0, 0.05, mass);
      assertTrue(retention > 0 && retention <= 1.0,
        "retention=" + retention + " out of (0,1] for coefficient=" + coefficient + " speed=" + speed + " fraction=" + fraction + " mass=" + mass);
     }
    }
   }
  }
 }
 @Test void directionalDragRetentionNeverOvershootsEvenAtTheExactDivergingStepsOwnExtremeSpeed() {
  // The exact gameTime=206/float1 incident this ticket's own investigation found overshooting
  // under the CURRENT explicit-impulse approach (see the test above) - applied through this
  // proposed retention-based alternative instead, THIS SCALAR VALUE still lands in (0,1], never
  // reversing or amplifying the local velocity. NOT claimed: that this alone reproduces the
  // current mechanism's own off-center torque coupling (54684's own point 3) - see the method's
  // own doc for that still-unresolved gap.
  double retention = Buoyancy.directionalDragRetention(43.90, 1.0, 400, 1.0, 0.05, 400);
  assertTrue(retention > 0 && retention <= 1.0, "retention=" + retention + " must stay in (0,1], never reversing or amplifying velocity");
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

 // MINECRAFT-545/547 (John's comment 54691): the energy-based dissipativity check and its
 // derived max-dissipative-impulse bound - John's own formula
 // (dKE = J*s + 0.5*k*J^2) exercised directly, not reimplemented differently in tests.

 @Test void anImpulseAtExactlyTheMaxDissipativeBoundIsEnergyNeutralNotPositive() {
  // The boundary itself must be <= 0 (energy-neutral, the documented tradeoff - not a safety
  // margin below it), never > 0, for a range of realistic s/k values including this incident's
  // own real numbers (k derived from this incident's own mass=400 as a simple 1/mass stand-in
  // for a direction with no rotational coupling, i.e. the COM-only special case).
  for (double s : new double[]{1, 16.66, 43.90, 1000}) {
   for (double k : new double[]{1.0 / 400, 1.0 / 50, 1.0, 0.01}) {
    double bound = Buoyancy.maxDissipativeImpulseMagnitude(s, k);
    double opposingImpulse = -Math.signum(s) * bound;
    double energyChange = Buoyancy.impulseEnergyChange(opposingImpulse, s, k);
    assertTrue(energyChange <= 1e-6, "energyChange=" + energyChange + " must be <=0 (within fp tolerance) at the documented boundary for s=" + s + " k=" + k);
   }
  }
 }

 @Test void anImpulseWellBelowTheMaxDissipativeBoundIsStrictlyDissipative() {
  double s = 43.90, k = 1.0 / 400; // this incident's own real COM-only numbers
  double bound = Buoyancy.maxDissipativeImpulseMagnitude(s, k);
  double smallOpposingImpulse = -Math.signum(s) * bound * 0.1;
  assertTrue(Buoyancy.isImpulseDissipative(smallOpposingImpulse, s, k));
  assertTrue(Buoyancy.impulseEnergyChange(smallOpposingImpulse, s, k) < 0, "a real fraction of the bound must strictly REMOVE energy, not merely stay at zero");
 }

 @Test void anImpulsePastTheMaxDissipativeBoundAddsEnergy() {
  // The exact converse - confirms the bound is not merely "a number," it is the real threshold
  // past which the SAME formula reports energy being ADDED, not removed.
  double s = 43.90, k = 1.0 / 400;
  double bound = Buoyancy.maxDissipativeImpulseMagnitude(s, k);
  double tooLargeOpposingImpulse = -Math.signum(s) * bound * 1.5;
  assertFalse(Buoyancy.isImpulseDissipative(tooLargeOpposingImpulse, s, k));
  assertTrue(Buoyancy.impulseEnergyChange(tooLargeOpposingImpulse, s, k) > 0);
 }

 @Test void summingImpulseEnergyChangePerImpulseAgainstTheSameSnapshotDoesNotPredictTheirActualCombinedEffect() {
  // MINECRAFT-545/547, John's comment 54694: a prior version of this test (confirmed WRONG and
  // REMOVED) claimed that summing impulseEnergyChange evaluated SEPARATELY for two impulses
  // against the same velocity snapshot predicts their combined effect, reasoning that each
  // impulse was individually below maxDissipativeImpulseMagnitude. That reasoning drops a real
  // quadratic cross-term: 0.5*k*(J1+J2)^2 != 0.5*k*J1^2 + 0.5*k*J2^2 whenever J1 and J2 are both
  // nonzero. This is John's own exact counter-example, independently re-verified numerically
  // (python3) before this fix: mass=1 (k=1), s=1, two EQUAL collinear impulses J1=J2=-1.5 (each
  // individually at exactly 0.75x its own solo bound of 2, i.e. "safely" inside it).
  double s = 1.0, k = 1.0;
  double j1 = -1.5, j2 = -1.5;
  double individuallyPredictedSum = Buoyancy.impulseEnergyChange(j1, s, k) + Buoyancy.impulseEnergyChange(j2, s, k);
  assertEquals(-0.75, individuallyPredictedSum, 1e-9, "each impulse alone looks safely dissipative when summed naively");
  // The TRUE combined effect: applying both impulses to the SAME body changes velocity once,
  // by their sum, not twice independently. v' = s + (j1+j2)*k = 1 - 3 = -2. KE_before = 0.5*1*1^2
  // = 0.5, KE_after = 0.5*1*(-2)^2 = 2.0 -> actual change = +1.5 (energy ADDED, not removed) -
  // the exact opposite sign from the naive per-impulse sum above.
  double massForThisPointMotion = 1.0 / k; // k here is a plain inverse mass (point coincides with COM, no rotation)
  double vBefore = s, vAfter = s + (j1 + j2) * k;
  double keBefore = 0.5 * massForThisPointMotion * vBefore * vBefore;
  double keAfter = 0.5 * massForThisPointMotion * vAfter * vAfter;
  double actualCombinedEnergyChange = keAfter - keBefore;
  assertEquals(1.5, actualCombinedEnergyChange, 1e-9);
  assertTrue(individuallyPredictedSum < 0 && actualCombinedEnergyChange > 0,
    "the naive per-impulse sum and the true combined effect disagree even in SIGN - summing separately is not a valid multi-impulse check");
  // The CORRECT way to predict the combined effect: call impulseEnergyChange ONCE with the SUM
  // of the impulses, never by summing separate per-impulse calls against the same snapshot.
  assertEquals(actualCombinedEnergyChange, Buoyancy.impulseEnergyChange(j1 + j2, s, k), 1e-9,
    "impulseEnergyChange(sum of impulses, s, k) must match the true combined effect exactly");
 }

 @Test void impulseEnergyChangeMatchesIndependentVectorRigidBodyEnergyForAnOffCenterImpulseOnAnAsymmetricBox() {
  // MINECRAFT-545/547, John's comments 54694/54697: a genuine VECTOR check, independent of
  // impulseEnergyChange's own scalar formula - computes kinetic energy directly from real
  // linear/angular velocity and a real (non-uniform, i.e. asymmetric) solid-box inertia tensor,
  // via 0.5*m*|v|^2 + 0.5*omega.(I*omega), for an impulse applied off-center (so it changes both
  // linear and angular velocity, not just linear) - then confirms that independently-computed
  // energy change matches impulseEnergyChange(J, s, k), where s and k are obtained by calling
  // the REAL MassData.getInverseNormalMass default method (54697: a prior version of this test
  // hand-derived the cross/dot formula manually instead of invoking the actual API this fix is
  // scoped to use - fixed here).
  double mass = 400.0;
  org.joml.Vector3d halfExtents = new org.joml.Vector3d(2.0, 1.0, 4.0); // asymmetric: not a cube, distinct per-axis inertia
  dev.ryanhcode.sable.api.physics.mass.MassData massData = TestMassData.solidBox(mass, halfExtents);
  org.joml.Matrix3dc inertia = massData.getInertiaTensor();
  org.joml.Matrix3dc inverseInertia = massData.getInverseInertiaTensor();
  double inverseMass = massData.getInverseMass();

  org.joml.Vector3d arm = new org.joml.Vector3d(1.5, -0.8, 3.0); // off-center application point, relative to COM
  org.joml.Vector3d direction = new org.joml.Vector3d(0.0, 1.0, 0.0).normalize(); // impulse direction (unit)

  org.joml.Vector3d linearVelocityBefore = new org.joml.Vector3d(2.0, -1.0, 0.5);
  org.joml.Vector3d angularVelocityBefore = new org.joml.Vector3d(0.1, 0.2, -0.3);

  // s: pre-impulse point-velocity component along direction, v_point = v + omega x r
  org.joml.Vector3d pointVelocityBefore = new org.joml.Vector3d(angularVelocityBefore).cross(arm).add(linearVelocityBefore);
  double s = pointVelocityBefore.dot(direction);

  // k: inverse effective mass at this point/direction, via the REAL Sable default method -
  // not a hand-derived reimplementation of its cross/dot formula.
  double k = massData.getInverseNormalMass(arm, direction);

  // 54697: the prior version of this test picked J=-27.3 against s=-1.75 - SAME sign, so J was
  // AIDING s, not opposing it, despite the comment claiming otherwise. Fixed: derive J from the
  // bound itself, scaled below 1.0, which is guaranteed (by construction) to oppose s.
  double bound = Buoyancy.maxDissipativeImpulseMagnitude(s, k);
  double jSum = -Math.signum(s) * bound * 0.6;
  assertTrue(Math.signum(jSum) != Math.signum(s), "the chosen impulse must actually oppose s, not aid it");
  double predictedEnergyChange = Buoyancy.impulseEnergyChange(jSum, s, k);

  // Independently apply the impulse as a real rigid-body linear+angular update and recompute KE
  // directly from vectors/tensors, with NO reuse of impulseEnergyChange's own scalar formula.
  org.joml.Vector3d impulseVector = new org.joml.Vector3d(direction).mul(jSum);
  org.joml.Vector3d angularImpulseVector = new org.joml.Vector3d(arm).cross(impulseVector);
  org.joml.Vector3d linearVelocityAfter = new org.joml.Vector3d(linearVelocityBefore).add(new org.joml.Vector3d(impulseVector).mul(inverseMass));
  org.joml.Vector3d angularVelocityAfter = new org.joml.Vector3d(angularVelocityBefore).add(inverseInertia.transform(new org.joml.Vector3d(angularImpulseVector)));

  double keBefore = 0.5 * mass * linearVelocityBefore.lengthSquared()
    + 0.5 * angularVelocityBefore.dot(inertia.transform(new org.joml.Vector3d(angularVelocityBefore)));
  double keAfter = 0.5 * mass * linearVelocityAfter.lengthSquared()
    + 0.5 * angularVelocityAfter.dot(inertia.transform(new org.joml.Vector3d(angularVelocityAfter)));
  double actualEnergyChange = keAfter - keBefore;

  assertEquals(actualEnergyChange, predictedEnergyChange, 1e-6,
    "impulseEnergyChange's scalar prediction must match independently-computed vector rigid-body energy change for an off-center impulse on an asymmetric box");
 }

 @Test void impulseEnergyChangeMatchesIndependentVectorEnergyWhenTheImpulseDirectionIsNotAlignedWithAnyPrincipalAxis() {
  // 54697: the prior test used only an axis-aligned impulse direction against a diagonal
  // (unrotated) inertia tensor, where the cross-coupling terms the inverse-inertia transform
  // can produce are mostly invisible. This test uses a direction with components on all three
  // axes, so getInverseNormalMass's matrix-vector transform is genuinely exercised, not merely
  // a disguised scalar divide.
  double mass = 250.0;
  org.joml.Vector3d halfExtents = new org.joml.Vector3d(3.0, 0.75, 1.5);
  dev.ryanhcode.sable.api.physics.mass.MassData massData = TestMassData.solidBox(mass, halfExtents);
  org.joml.Matrix3dc inertia = massData.getInertiaTensor();
  org.joml.Matrix3dc inverseInertia = massData.getInverseInertiaTensor();
  double inverseMass = massData.getInverseMass();

  org.joml.Vector3d arm = new org.joml.Vector3d(-2.2, 1.1, -0.9);
  org.joml.Vector3d direction = new org.joml.Vector3d(0.4, 0.6, -0.69).normalize(); // not axis-aligned

  org.joml.Vector3d linearVelocityBefore = new org.joml.Vector3d(43.90, -12.0, 7.5); // incident-scale
  org.joml.Vector3d angularVelocityBefore = new org.joml.Vector3d(1.5, -2.0, 0.8);

  org.joml.Vector3d pointVelocityBefore = new org.joml.Vector3d(angularVelocityBefore).cross(arm).add(linearVelocityBefore);
  double s = pointVelocityBefore.dot(direction);
  double k = massData.getInverseNormalMass(arm, direction);

  double bound = Buoyancy.maxDissipativeImpulseMagnitude(s, k);
  double jSum = -Math.signum(s) * bound * 1.3; // deliberately PAST the bound, should add energy
  double predictedEnergyChange = Buoyancy.impulseEnergyChange(jSum, s, k);

  org.joml.Vector3d impulseVector = new org.joml.Vector3d(direction).mul(jSum);
  org.joml.Vector3d angularImpulseVector = new org.joml.Vector3d(arm).cross(impulseVector);
  org.joml.Vector3d linearVelocityAfter = new org.joml.Vector3d(linearVelocityBefore).add(new org.joml.Vector3d(impulseVector).mul(inverseMass));
  org.joml.Vector3d angularVelocityAfter = new org.joml.Vector3d(angularVelocityBefore).add(inverseInertia.transform(new org.joml.Vector3d(angularImpulseVector)));

  double keBefore = 0.5 * mass * linearVelocityBefore.lengthSquared()
    + 0.5 * angularVelocityBefore.dot(inertia.transform(new org.joml.Vector3d(angularVelocityBefore)));
  double keAfter = 0.5 * mass * linearVelocityAfter.lengthSquared()
    + 0.5 * angularVelocityAfter.dot(inertia.transform(new org.joml.Vector3d(angularVelocityAfter)));
  double actualEnergyChange = keAfter - keBefore;

  assertTrue(actualEnergyChange > 0, "an impulse deliberately past its own dissipative bound must add energy");
  assertEquals(actualEnergyChange, predictedEnergyChange, 1e-6,
    "impulseEnergyChange's scalar prediction must match the independent vector result even for a non-axis-aligned direction/arm");
 }

 @Test void maxDissipativeImpulseMagnitudeIsZeroForNonPositiveOrNonFiniteEffectiveMass() {
  // No positive bound is well-defined without a positive effective mass to divide by -
  // including the non-finite (NaN/Infinite) case explicitly, not merely zero/negative.
  assertEquals(0, Buoyancy.maxDissipativeImpulseMagnitude(50, 0));
  assertEquals(0, Buoyancy.maxDissipativeImpulseMagnitude(50, -1));
  assertEquals(0, Buoyancy.maxDissipativeImpulseMagnitude(50, Double.NaN));
  assertEquals(0, Buoyancy.maxDissipativeImpulseMagnitude(50, Double.NEGATIVE_INFINITY));
 }

 @Test void maxDissipativeImpulseMagnitudeIsZeroWhenTheFiniteInputsWouldOverflowTheResultItself() {
  // 54697: finite, individually-valid inputs can still make 2*|s|/k overflow double range -
  // the input guards above don't catch this, since both s and k are finite here. The RESULT
  // must also be guarded, conservatively, the same way every other invalid case returns 0.
  assertEquals(0, Buoyancy.maxDissipativeImpulseMagnitude(Double.MAX_VALUE, 1.0));
  assertEquals(0, Buoyancy.maxDissipativeImpulseMagnitude(-Double.MAX_VALUE, 1.0));
  assertEquals(0, Buoyancy.maxDissipativeImpulseMagnitude(Double.MAX_VALUE, Double.MIN_VALUE));
  // a merely large, non-overflowing case must still compute normally, not get caught by the guard
  assertEquals(2 * 1.0e200 / 1.0, Buoyancy.maxDissipativeImpulseMagnitude(1.0e200, 1.0), 1e-9);
 }

 @Test void zeroPreImpulseVelocityHasNoDissipativeImpulseBudget() {
  // At rest along this direction (s=0), ANY nonzero impulse only adds energy (0.5*k*J^2 > 0 for
  // J!=0) - the bound correctly collapses to 0, not some positive "free" allowance.
  assertEquals(0, Buoyancy.maxDissipativeImpulseMagnitude(0, 1.0 / 400));
  assertFalse(Buoyancy.isImpulseDissipative(1.0, 0, 1.0 / 400));
  assertTrue(Buoyancy.isImpulseDissipative(0, 0, 1.0 / 400));
 }
}
