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
}
