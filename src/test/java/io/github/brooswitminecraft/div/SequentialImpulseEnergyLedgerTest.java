package io.github.brooswitminecraft.div;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MINECRAFT-545/547, John's comment 54697: tests for the UNWIRED sequential-impulse energy
 * ledger candidate - independently recomputes before/after kinetic energy for multiple
 * nonparallel, off-center impulses on a rotated/asymmetric box at incident-scale velocities,
 * via the actual {@code MassData} API this fix is scoped to use. Not wired into any production
 * path; these tests exercise the candidate in isolation.
 */
class SequentialImpulseEnergyLedgerTest {

 @Test void boundingEachFloatsImpulseAgainstTheCurrentStateKeepsTheirRealCombinedEffectNonPositive() {
  // The exact scenario John's comment 54691 originally flagged: multiple floats, each at a
  // different off-center arm/direction, each individually bounded - but checked here against
  // the sequential model the native engine actually implements (confirmed via lib.rs), not a
  // shared stale snapshot. Three nonparallel, off-center impulses on an asymmetric box at
  // incident-scale velocity.
  double mass = 400.0;
  Vector3d halfExtents = new Vector3d(2.0, 1.0, 4.0);
  MassData massData = TestMassData.solidBox(mass, halfExtents);
  SequentialImpulseEnergyLedger ledger = new SequentialImpulseEnergyLedger(
    massData, new Vector3d(43.90, -5.0, 2.0), new Vector3d(0.3, -0.5, 0.2));

  Vector3d[] arms = {
    new Vector3d(1.8, -0.9, 3.5),
    new Vector3d(-1.8, -0.9, 3.5),
    new Vector3d(0.0, 0.9, -3.5),
  };
  Vector3d[] directions = {
    new Vector3d(0.0, 1.0, 0.0),
    new Vector3d(0.2, 0.9, 0.1).normalize(),
    new Vector3d(-0.1, 0.95, -0.2).normalize(),
  };

  double totalEnergyChange = 0;
  for (int i = 0; i < arms.length; i++) {
   double bound = ledger.currentMaxDissipativeImpulseMagnitude(arms[i], directions[i]);
   Vector3d pointVelocity = new Vector3d(ledger.angularVelocity()).cross(arms[i]).add(new Vector3d(ledger.linearVelocity()));
   double s = pointVelocity.dot(directions[i]);
   double impulseMagnitude = -Math.signum(s) * bound * 0.7; // stay safely inside the CURRENT bound
   totalEnergyChange += ledger.applyImpulseAndMeasureEnergyChange(arms[i], directions[i], impulseMagnitude);
  }

  assertTrue(totalEnergyChange <= 1e-9,
    "every float's impulse bounded against the real, sequentially-updated state must keep the true combined energy change non-positive");
 }

 @Test void applyingASecondImpulseChangesTheBoundForAFollowingFloatAtTheSamePointAndDirection() {
  // Confirms the ledger's bound genuinely reflects the sequential model, not a frozen snapshot:
  // after one impulse changes the velocity, a second bound computed at the SAME point/direction
  // must differ from the first (the entire reason the naive shared-snapshot approach, 54694,
  // was wrong).
  double mass = 400.0;
  Vector3d halfExtents = new Vector3d(2.0, 1.0, 4.0);
  MassData massData = TestMassData.solidBox(mass, halfExtents);
  SequentialImpulseEnergyLedger ledger = new SequentialImpulseEnergyLedger(
    massData, new Vector3d(43.90, 0, 0), new Vector3d(0.1, 0.2, -0.3)); // angular velocity makes s!=0 along y at this arm

  Vector3d arm = new Vector3d(1.5, -0.8, 3.0);
  Vector3d direction = new Vector3d(0, 1, 0);

  double boundBefore = ledger.currentMaxDissipativeImpulseMagnitude(arm, direction);
  ledger.applyImpulseAndMeasureEnergyChange(arm, direction, -boundBefore * 0.5);
  double boundAfter = ledger.currentMaxDissipativeImpulseMagnitude(arm, direction);

  assertNotEquals(boundBefore, boundAfter, 1e-9,
    "the bound at the same point/direction must change once an earlier impulse has updated the real velocity state");
 }

 @Test void anImpulseKeptWithinItsOwnCurrentBoundNeverIncreasesKineticEnergyForThatSingleApplication() {
  // Each individual application, checked against ITS OWN current-state bound at the moment it
  // is applied, must itself be non-energy-adding - the ledger's bound is evaluated fresh each
  // time, exactly matching what applyImpulseAndMeasureEnergyChange actually measures.
  double mass = 250.0;
  Vector3d halfExtents = new Vector3d(3.0, 0.75, 1.5);
  MassData massData = TestMassData.solidBox(mass, halfExtents);
  SequentialImpulseEnergyLedger ledger = new SequentialImpulseEnergyLedger(
    massData, new Vector3d(-12.0, 59.09, 20.15), new Vector3d(1.5, -2.0, 0.8)); // incident-scale

  Vector3d arm = new Vector3d(-2.2, 1.1, -0.9);
  Vector3d direction = new Vector3d(0.4, 0.6, -0.69).normalize();

  double bound = ledger.currentMaxDissipativeImpulseMagnitude(arm, direction);
  Vector3d pointVelocity = new Vector3d(ledger.angularVelocity()).cross(arm).add(new Vector3d(ledger.linearVelocity()));
  double s = pointVelocity.dot(direction);
  double energyChange = ledger.applyImpulseAndMeasureEnergyChange(arm, direction, -Math.signum(s) * bound);

  assertTrue(energyChange <= 1e-6, "an impulse exactly at its own current bound must not increase kinetic energy");
 }

 @Test void exceedingTheCurrentBoundForASingleApplicationDoesAddEnergy() {
  // Sanity check on the other direction - the ledger's bound is a real, exercised boundary,
  // not a value that happens to always pass regardless of the impulse chosen.
  double mass = 400.0;
  Vector3d halfExtents = new Vector3d(2.0, 1.0, 4.0);
  MassData massData = TestMassData.solidBox(mass, halfExtents);
  SequentialImpulseEnergyLedger ledger = new SequentialImpulseEnergyLedger(
    massData, new Vector3d(43.90, 0, 0), new Vector3d(0.1, 0.2, -0.3)); // angular velocity makes s!=0 along y at this arm

  Vector3d arm = new Vector3d(1.5, -0.8, 3.0);
  Vector3d direction = new Vector3d(0, 1, 0);

  double bound = ledger.currentMaxDissipativeImpulseMagnitude(arm, direction);
  Vector3d pointVelocity = new Vector3d(ledger.angularVelocity()).cross(arm).add(new Vector3d(ledger.linearVelocity()));
  double s = pointVelocity.dot(direction);
  double energyChange = ledger.applyImpulseAndMeasureEnergyChange(arm, direction, -Math.signum(s) * bound * 1.5);

  assertTrue(energyChange > 0, "an impulse deliberately past the current bound must add energy");
 }

 @Test void aZeroOrNonFiniteDirectionIsRejected() {
  // 54700: direction is normalized internally - a zero-length or non-finite direction has no
  // meaningful normalized form, and must be rejected rather than silently producing NaN/Infinity.
  MassData massData = TestMassData.solidBox(400.0, new Vector3d(2, 1, 4));
  SequentialImpulseEnergyLedger ledger = new SequentialImpulseEnergyLedger(massData, new Vector3d(1, 0, 0), new Vector3d(0, 0, 0));
  Vector3d arm = new Vector3d(1, 0, 0);
  assertThrows(IllegalArgumentException.class, () -> ledger.currentMaxDissipativeImpulseMagnitude(arm, new Vector3d(0, 0, 0)));
  assertThrows(IllegalArgumentException.class, () -> ledger.applyImpulseAndMeasureEnergyChange(arm, new Vector3d(0, 0, 0), 1.0));
  assertThrows(IllegalArgumentException.class, () -> ledger.currentMaxDissipativeImpulseMagnitude(arm, new Vector3d(Double.NaN, 0, 0)));
  assertThrows(IllegalArgumentException.class, () -> ledger.applyImpulseAndMeasureEnergyChange(arm, new Vector3d(Double.POSITIVE_INFINITY, 0, 0), 1.0));
 }

 @Test void aNonUnitLengthDirectionProducesTheSamePhysicalResultAsItsNormalizedEquivalent() {
  // 54700: a prior version of currentMaxDissipativeImpulseMagnitude used the RAW direction for
  // the velocity-component side (s) while MassData.getInverseNormalMass silently normalizes its
  // own copy for the effective-mass side (k) - an inconsistency that would silently corrupt the
  // result for any non-unit-length caller input. Confirms the SAME physical direction gives the
  // SAME result regardless of the vector's own magnitude.
  double mass = 250.0;
  Vector3d halfExtents = new Vector3d(3.0, 0.75, 1.5);
  MassData massData = TestMassData.solidBox(mass, halfExtents);
  Vector3d arm = new Vector3d(-2.2, 1.1, -0.9);
  Vector3d unitDirection = new Vector3d(0.4, 0.6, -0.69).normalize();
  Vector3d scaledDirection = new Vector3d(unitDirection).mul(7.3); // same direction, NOT unit length

  SequentialImpulseEnergyLedger ledgerA = new SequentialImpulseEnergyLedger(massData, new Vector3d(-12.0, 59.09, 20.15), new Vector3d(1.5, -2.0, 0.8));
  SequentialImpulseEnergyLedger ledgerB = new SequentialImpulseEnergyLedger(massData, new Vector3d(-12.0, 59.09, 20.15), new Vector3d(1.5, -2.0, 0.8));

  double boundA = ledgerA.currentMaxDissipativeImpulseMagnitude(arm, unitDirection);
  double boundB = ledgerB.currentMaxDissipativeImpulseMagnitude(arm, scaledDirection);
  assertEquals(boundA, boundB, 1e-9, "the bound must be identical regardless of the caller's direction vector's own length");

  double energyA = ledgerA.applyImpulseAndMeasureEnergyChange(arm, unitDirection, 5.0);
  double energyB = ledgerB.applyImpulseAndMeasureEnergyChange(arm, scaledDirection, 5.0);
  assertEquals(energyA, energyB, 1e-6, "applying the same signed magnitude along the same physical direction must give the same energy change regardless of the direction vector's own length");
  assertEquals(0, new Vector3d(ledgerA.linearVelocity()).sub(ledgerB.linearVelocity()).length(), 1e-9);
 }

 @Test void aNonZeroCenterOfMassIsHandledConsistentlyBetweenTheArmContractAndGetInverseNormalMass() {
  // 54700: "Define arm-vs-position/COM contract consistently, since MassData subtracts COM but
  // the ledger crosses arm directly." A prior version of the ledger passed its COM-relative
  // `arm` straight through as a `position` to getInverseNormalMass (which subtracts its own
  // getCenterOfMass() internally) - latent only because every earlier test's MassData returned
  // a zero COM. This test uses a deliberately non-origin COM and independently recomputes the
  // expected bound using the documented contract (position = arm + COM) to confirm the ledger
  // actually does this, not merely pass arm through.
  double mass = 300.0;
  Vector3d halfExtents = new Vector3d(1.5, 1.0, 2.5);
  Vector3d com = new Vector3d(0.3, -0.2, 0.1); // deliberately non-origin
  MassData massData = TestMassData.solidBoxWithCenterOfMass(mass, halfExtents, com);

  Vector3d arm = new Vector3d(1.2, -0.5, 2.0); // COM-relative, per the ledger's own documented contract
  Vector3d direction = new Vector3d(0.3, 0.9, -0.2).normalize();
  Vector3d linearVelocity = new Vector3d(20.0, -3.0, 5.0);
  Vector3d angularVelocity = new Vector3d(0.4, -0.6, 0.2);

  SequentialImpulseEnergyLedger ledger = new SequentialImpulseEnergyLedger(massData, linearVelocity, angularVelocity);
  double bound = ledger.currentMaxDissipativeImpulseMagnitude(arm, direction);

  Vector3d position = new Vector3d(arm).add(com);
  double expectedK = massData.getInverseNormalMass(position, direction);
  Vector3d pointVelocity = new Vector3d(angularVelocity).cross(arm).add(linearVelocity);
  double expectedS = pointVelocity.dot(direction);
  double expectedBound = Buoyancy.maxDissipativeImpulseMagnitude(expectedS, expectedK);

  assertEquals(expectedBound, bound, 1e-9,
    "the ledger must add the COM back onto the COM-relative arm before calling getInverseNormalMass, matching that method's own documented contract");
 }

 @Test void aRotatedWorldFrameSystemProducesTheSameEnergyChangeAsTheEquivalentLocalFrameSystem() {
  // 54700: "Complete genuinely rotated asymmetric-box covariance test: local-frame run and
  // equivalent rotated-world tensor/arms/directions/velocities must agree after transforming
  // back." A prior test only varied the impulse DIRECTION against a still-diagonal (unrotated)
  // tensor - this rotates the ENTIRE system (tensor, arm, direction, both velocities) by the
  // same arbitrary rotation and confirms both the scalar energy change and the full resulting
  // velocity vectors agree after transforming the rotated run's result back.
  double mass = 350.0;
  Vector3d halfExtents = new Vector3d(2.5, 1.0, 3.5); // asymmetric
  MassData localMassData = TestMassData.solidBox(mass, halfExtents);

  Vector3d localArm = new Vector3d(1.3, -0.7, 2.1);
  Vector3d localDirection = new Vector3d(0.2, 0.9, -0.3).normalize();
  Vector3d localLinearVelocity = new Vector3d(18.0, -6.0, 9.0);
  Vector3d localAngularVelocity = new Vector3d(0.8, -1.1, 0.5);
  double impulseMagnitude = -41.7;

  SequentialImpulseEnergyLedger localLedger = new SequentialImpulseEnergyLedger(localMassData, localLinearVelocity, localAngularVelocity);
  double localEnergyChange = localLedger.applyImpulseAndMeasureEnergyChange(localArm, localDirection, impulseMagnitude);

  org.joml.Quaterniond rotation = new org.joml.Quaterniond().rotateXYZ(0.4, -0.9, 1.3);
  MassData rotatedMassData = TestMassData.rotatedSolidBox(mass, halfExtents, rotation);
  Vector3d rotatedArm = rotation.transform(new Vector3d(localArm));
  Vector3d rotatedDirection = rotation.transform(new Vector3d(localDirection));
  Vector3d rotatedLinearVelocity = rotation.transform(new Vector3d(localLinearVelocity));
  Vector3d rotatedAngularVelocity = rotation.transform(new Vector3d(localAngularVelocity));

  SequentialImpulseEnergyLedger rotatedLedger = new SequentialImpulseEnergyLedger(rotatedMassData, rotatedLinearVelocity, rotatedAngularVelocity);
  double rotatedEnergyChange = rotatedLedger.applyImpulseAndMeasureEnergyChange(rotatedArm, rotatedDirection, impulseMagnitude);

  assertEquals(localEnergyChange, rotatedEnergyChange, 1e-6,
    "kinetic-energy change (a scalar) must be identical whether computed in the local frame or an equivalently-rotated world frame");

  org.joml.Quaterniond inverseRotation = new org.joml.Quaterniond(rotation).conjugate();
  Vector3d recoveredLinearVelocity = inverseRotation.transform(new Vector3d(rotatedLedger.linearVelocity()));
  Vector3d recoveredAngularVelocity = inverseRotation.transform(new Vector3d(rotatedLedger.angularVelocity()));

  assertEquals(0, new Vector3d(localLedger.linearVelocity()).sub(recoveredLinearVelocity).length(), 1e-6,
    "the rotated-frame result, transformed back, must match the local-frame linear velocity result exactly");
  assertEquals(0, new Vector3d(localLedger.angularVelocity()).sub(recoveredAngularVelocity).length(), 1e-6,
    "the rotated-frame result, transformed back, must match the local-frame angular velocity result exactly");
 }

 @Test void theSumOfReturnedEnergyChangesMatchesIndependentlyRecomputedTotalKineticEnergyChangeOutsideTheLedger() {
  // 54700: "Independently compute before/after total energy outside the ledger, not only sum
  // values returned by its own kineticEnergy method." This recomputes total KE before/after
  // using ONLY the ledger's exposed linearVelocity()/angularVelocity() plus the same MassData,
  // via a separate expression written here in the test - not by calling the class under test's
  // own private kineticEnergy() method - and confirms it matches the sum of per-impulse deltas.
  double mass = 400.0;
  Vector3d halfExtents = new Vector3d(2.0, 1.0, 4.0);
  MassData massData = TestMassData.solidBox(mass, halfExtents);
  Vector3d initialLinear = new Vector3d(43.90, -5.0, 2.0);
  Vector3d initialAngular = new Vector3d(0.3, -0.5, 0.2);
  SequentialImpulseEnergyLedger ledger = new SequentialImpulseEnergyLedger(massData, initialLinear, initialAngular);

  double keBeforeExternal = 0.5 * mass * initialLinear.lengthSquared()
    + 0.5 * initialAngular.dot(massData.getInertiaTensor().transform(new Vector3d(initialAngular), new Vector3d()));

  Vector3d[] arms = { new Vector3d(1.8, -0.9, 3.5), new Vector3d(-1.8, -0.9, 3.5), new Vector3d(0.0, 0.9, -3.5) };
  Vector3d[] directions = { new Vector3d(0, 1, 0), new Vector3d(0.2, 0.9, 0.1).normalize(), new Vector3d(-0.1, 0.95, -0.2).normalize() };

  double sumOfReturnedDeltas = 0;
  for (int i = 0; i < arms.length; i++) {
   double bound = ledger.currentMaxDissipativeImpulseMagnitude(arms[i], directions[i]);
   Vector3d pointVelocity = new Vector3d(ledger.angularVelocity()).cross(arms[i]).add(new Vector3d(ledger.linearVelocity()));
   double s = pointVelocity.dot(directions[i]);
   sumOfReturnedDeltas += ledger.applyImpulseAndMeasureEnergyChange(arms[i], directions[i], -Math.signum(s) * bound * 0.6);
  }

  Vector3d finalLinear = new Vector3d(ledger.linearVelocity());
  Vector3d finalAngular = new Vector3d(ledger.angularVelocity());
  double keAfterExternal = 0.5 * mass * finalLinear.lengthSquared()
    + 0.5 * finalAngular.dot(massData.getInertiaTensor().transform(new Vector3d(finalAngular), new Vector3d()));

  assertEquals(keAfterExternal - keBeforeExternal, sumOfReturnedDeltas, 1e-6,
    "the sum of each individual applyImpulseAndMeasureEnergyChange return value must match the TOTAL kinetic-energy change, recomputed independently from exposed state outside the ledger");
 }

 @Test void multipleFloatsWithMixedDirectionsStayJointlyNonPositiveAtIncidentScaleLinearAndAngularVelocity() {
  // 54700: "Include multi-float mixed directions and incident-scale angular as well as linear
  // speeds." The existing multi-float test used a modest angular velocity; this one uses
  // incident-scale magnitudes for BOTH linear and angular velocity (per the retained native
  // evidence from the second authorized run - |angularVelocity| reached the tens of rad/s), and
  // four floats with genuinely mixed directions (including a negative-component direction).
  double mass = 400.0;
  Vector3d halfExtents = new Vector3d(2.0, 1.0, 4.0);
  MassData massData = TestMassData.solidBox(mass, halfExtents);
  SequentialImpulseEnergyLedger ledger = new SequentialImpulseEnergyLedger(
    massData, new Vector3d(59.09, -20.15, 10.0), new Vector3d(12.0, -34.16, 8.0));

  Vector3d[] arms = {
   new Vector3d(1.8, -0.9, 3.5),
   new Vector3d(-1.8, -0.9, 3.5),
   new Vector3d(0.0, 0.9, -3.5),
   new Vector3d(1.8, 0.9, -3.5),
  };
  Vector3d[] directions = {
   new Vector3d(0.0, 1.0, 0.0),
   new Vector3d(0.3, 0.8, -0.2).normalize(),
   new Vector3d(-0.4, 0.7, 0.3).normalize(),
   new Vector3d(0.1, -0.9, 0.5).normalize(),
  };

  double totalEnergyChange = 0;
  for (int i = 0; i < arms.length; i++) {
   double bound = ledger.currentMaxDissipativeImpulseMagnitude(arms[i], directions[i]);
   Vector3d pointVelocity = new Vector3d(ledger.angularVelocity()).cross(arms[i]).add(new Vector3d(ledger.linearVelocity()));
   double s = pointVelocity.dot(directions[i]);
   totalEnergyChange += ledger.applyImpulseAndMeasureEnergyChange(arms[i], directions[i], -Math.signum(s) * bound * 0.7);
  }

  assertTrue(totalEnergyChange <= 1e-6,
    "four mixed-direction floats, each bounded against live sequential state at incident-scale linear AND angular velocity, must keep the true combined energy change non-positive");
 }
}
