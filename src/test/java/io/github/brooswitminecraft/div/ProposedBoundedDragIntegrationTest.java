package io.github.brooswitminecraft.div;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MINECRAFT-545/547, John's comments 54700/54703: tests for the UNWIRED production-integration
 * design {@link ProposedBoundedDragIntegration}. Not called from any production path.
 */
class ProposedBoundedDragIntegrationTest {
 private static final double DT = 0.05;

 @Test void aDragForceAlreadyWithinBoundIsReturnedUnchanged() {
  double mass = 400.0;
  MassData massData = TestMassData.solidBox(mass, new Vector3d(2.0, 1.0, 4.0));
  Vector3d arm = new Vector3d(1.5, -0.8, 3.0);
  Vector3d linearVelocity = new Vector3d(0, 43.90, 0); // +y, so a -y drag OPPOSES it (s<0)
  Vector3d angularVelocity = new Vector3d(0, 0, 0);
  // Opposing direction (-y) with a tiny magnitude - certainly within bound.
  Vector3d smallDrag = new Vector3d(0, -0.01, 0);

  Vector3d result = ProposedBoundedDragIntegration.boundedDragForce(massData, arm, smallDrag, linearVelocity, angularVelocity, DT);

  assertEquals(0, new Vector3d(smallDrag).sub(result).length(), 1e-12, "a drag force already well within bound must be returned unchanged");
 }

 @Test void aDragForceExceedingTheBoundIsScaledDownToExactlyArrestTheOpposedPointVelocityComponentNotReverseIt() {
  // 54703: a prior version capped at the ENERGY-NEUTRAL bound, which (confirmed by its own
  // test asserting exact equality) lands exactly at the reversal boundary - the opposite of the
  // "strictly interior" claim in its own doc. Fixed to cap at maxNonReversingImpulseMagnitude:
  // the resulting impulse must drive the opposed point-velocity component to EXACTLY ZERO, not
  // reverse it - confirmed here directly from the resulting velocity, not just the impulse math.
  double mass = 400.0;
  MassData massData = TestMassData.solidBox(mass, new Vector3d(2.0, 1.0, 4.0));
  Vector3d arm = new Vector3d(1.5, -0.8, 3.0);
  Vector3d linearVelocity = new Vector3d(0, 43.90, 0);
  Vector3d angularVelocity = new Vector3d(0, 0, 0);
  Vector3d largeDrag = new Vector3d(0, -2_000_000.0, 0); // deliberately far past any real bound here

  Vector3d result = ProposedBoundedDragIntegration.boundedDragForce(massData, arm, largeDrag, linearVelocity, angularVelocity, DT);

  assertTrue(result.length() < largeDrag.length(), "an over-bound drag force must be scaled down");
  assertTrue(result.y < 0, "direction must be preserved - still opposing +y");

  // Independently apply the RESULTING (scaled) impulse via the ledger and confirm the
  // point-velocity component along the drag direction lands at exactly zero, not reversed.
  SequentialImpulseEnergyLedger ledger = new SequentialImpulseEnergyLedger(massData, linearVelocity, angularVelocity);
  Vector3d direction = new Vector3d(largeDrag).normalize(); // points -y, same direction result itself points
  ledger.applyImpulseAndMeasureEnergyChange(arm, direction, result.length() * DT); // positive forward magnitude along that same direction
  Vector3d pointVelocityAfter = new Vector3d(ledger.angularVelocity()).cross(arm).add(new Vector3d(ledger.linearVelocity()));
  double sAfter = pointVelocityAfter.dot(direction);
  assertEquals(0, sAfter, 1e-6, "the opposed point-velocity component must land at exactly zero, never past it into reversal");
 }

 @Test void aDragForceThatAidsRatherThanOpposesThePointVelocityIsScaledEntirelyToZero() {
  double mass = 400.0;
  MassData massData = TestMassData.solidBox(mass, new Vector3d(2.0, 1.0, 4.0));
  Vector3d arm = new Vector3d(1.5, -0.8, 3.0);
  Vector3d linearVelocity = new Vector3d(0, 43.90, 0); // +y point velocity
  Vector3d angularVelocity = new Vector3d(0, 0, 0);
  Vector3d aidingDrag = new Vector3d(0, 50.0, 0); // SAME sign as the velocity - aiding, not opposing

  Vector3d result = ProposedBoundedDragIntegration.boundedDragForce(massData, arm, aidingDrag, linearVelocity, angularVelocity, DT);

  assertEquals(0, result.length(), 1e-12, "a drag force that aids (not opposes) the point velocity must be scaled entirely to zero");
 }

 @Test void aZeroDragForceIsReturnedUnchanged() {
  double mass = 400.0;
  MassData massData = TestMassData.solidBox(mass, new Vector3d(2.0, 1.0, 4.0));
  Vector3d arm = new Vector3d(1.5, -0.8, 3.0);
  Vector3d result = ProposedBoundedDragIntegration.boundedDragForce(
    massData, arm, new Vector3d(0, 0, 0), new Vector3d(43.90, 0, 0), new Vector3d(0, 0, 0), DT);
  assertEquals(0, result.length(), 1e-12);
 }

 @Test void scalingNeverProducesAMagnitudeLargerThanThePhysicalForceItself() {
  double mass = 250.0;
  MassData massData = TestMassData.solidBox(mass, new Vector3d(3.0, 0.75, 1.5));
  Vector3d arm = new Vector3d(-2.2, 1.1, -0.9);
  Vector3d linearVelocity = new Vector3d(-12.0, 59.09, 20.15);
  Vector3d angularVelocity = new Vector3d(1.5, -2.0, 0.8);
  Vector3d drag = new Vector3d(0.4, 0.6, -0.69).normalize().mul(37.2);

  Vector3d result = ProposedBoundedDragIntegration.boundedDragForce(massData, arm, drag, linearVelocity, angularVelocity, DT);

  assertTrue(result.length() <= drag.length() + 1e-9, "the result's magnitude must never exceed the physical force's own magnitude");
 }

 @Test void anInvalidDtProducesAConservativeZeroNotNaNOrTheRawForceUnchanged() {
  // 54703: "Invalid dt (zero/negative/nonfinite) ... must have an explicit safe contract;
  // ... NaN dt can produce NaN output" - a prior version had no guard for this at all.
  double mass = 400.0;
  MassData massData = TestMassData.solidBox(mass, new Vector3d(2.0, 1.0, 4.0));
  Vector3d arm = new Vector3d(1.5, -0.8, 3.0);
  Vector3d linearVelocity = new Vector3d(0, 43.90, 0);
  Vector3d angularVelocity = new Vector3d(0, 0, 0);
  Vector3d drag = new Vector3d(0, -50.0, 0);

  for (double badDt : new double[] {0.0, -0.05, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
   Vector3d result = ProposedBoundedDragIntegration.boundedDragForce(massData, arm, drag, linearVelocity, angularVelocity, badDt);
   assertTrue(result.isFinite(), "an invalid dt (" + badDt + ") must never produce a non-finite result");
   assertEquals(0, result.length(), 1e-12, "an invalid dt (" + badDt + ") has no well-defined budget - must return a conservative zero, not the raw force unchanged");
  }
 }

 @Test void aNonFiniteDragForceProducesAConservativeZeroNotReturnedUnchanged() {
  // 54703: "current NaN force can be returned unchanged" - a prior version's zero-magnitude
  // guard (`!(forceMagnitude > 0)`) let a NaN-containing force slip through as "unchanged"
  // because any comparison against NaN is false, so the "exceeds bound" check never triggered.
  double mass = 400.0;
  MassData massData = TestMassData.solidBox(mass, new Vector3d(2.0, 1.0, 4.0));
  Vector3d arm = new Vector3d(1.5, -0.8, 3.0);
  Vector3d linearVelocity = new Vector3d(0, 43.90, 0);
  Vector3d angularVelocity = new Vector3d(0, 0, 0);

  Vector3d nanDrag = new Vector3d(0, Double.NaN, 0);
  Vector3d result = ProposedBoundedDragIntegration.boundedDragForce(massData, arm, nanDrag, linearVelocity, angularVelocity, DT);
  assertTrue(result.isFinite(), "a non-finite drag force must never produce a non-finite result");
  assertEquals(0, result.length(), 1e-12, "a non-finite drag force has no well-defined budget - must return a conservative zero");
 }

 @Test void multipleFloatsClampedSequentiallyKeepTheirTrueCombinedEnergyChangeNonPositiveAndNoPointVelocityReverses() {
  // 54703: "test actual before/after energy and point velocity when clamping is active,
  // including multiple floats." Applies the CLAMPED result for several floats, in sequence
  // (via the real sequential ledger, matching confirmed native application order), and checks
  // both the true combined energy change and that no float's own opposed point-velocity
  // component is driven past zero into reversal.
  double mass = 400.0;
  Vector3d halfExtents = new Vector3d(2.0, 1.0, 4.0);
  MassData massData = TestMassData.solidBox(mass, halfExtents);
  SequentialImpulseEnergyLedger ledger = new SequentialImpulseEnergyLedger(
    massData, new Vector3d(0, 59.09, 10.0), new Vector3d(5.0, -8.0, 3.0));

  Vector3d[] arms = {
   new Vector3d(1.8, -0.9, 3.5),
   new Vector3d(-1.8, -0.9, 3.5),
   new Vector3d(0.0, 0.9, -3.5),
  };
  // Deliberately oversized physical drag forces - each would far exceed its own bound unclamped.
  Vector3d[] physicalDrags = {
   new Vector3d(0, -5_000_000.0, 0),
   new Vector3d(300_000.0, -4_000_000.0, 100_000.0),
   new Vector3d(-200_000.0, -3_000_000.0, 150_000.0),
  };

  double totalEnergyChange = 0;
  for (int i = 0; i < arms.length; i++) {
   Vector3d freshLinear = new Vector3d(ledger.linearVelocity());
   Vector3d freshAngular = new Vector3d(ledger.angularVelocity());
   Vector3d clamped = ProposedBoundedDragIntegration.boundedDragForce(massData, arms[i], physicalDrags[i], freshLinear, freshAngular, DT);
   Vector3d impulseVector = new Vector3d(clamped).mul(DT);
   double impulseMagnitude = impulseVector.length();
   if (impulseMagnitude == 0) continue;
   Vector3d direction = new Vector3d(clamped).normalize();

   // Confirm no reversal BEFORE applying, using the ledger's own live state (matching what
   // boundedDragForce itself just bounded against).
   Vector3d pointVelocityBefore = new Vector3d(ledger.angularVelocity()).cross(arms[i]).add(new Vector3d(ledger.linearVelocity()));
   double sBefore = pointVelocityBefore.dot(direction);

   totalEnergyChange += ledger.applyImpulseAndMeasureEnergyChange(arms[i], direction, impulseMagnitude);

   Vector3d pointVelocityAfter = new Vector3d(ledger.angularVelocity()).cross(arms[i]).add(new Vector3d(ledger.linearVelocity()));
   double sAfter = pointVelocityAfter.dot(direction);
   if (sBefore < 0) {
    assertTrue(sAfter >= sBefore - 1e-6 && sAfter <= 1e-6,
     "a clamped, opposing drag impulse must move the point-velocity component toward zero and never past it into the opposite sign");
   }
  }

  assertTrue(totalEnergyChange <= 1e-6,
   "three floats' clamped drag impulses, applied sequentially against live state, must keep the true combined energy change non-positive");
 }
}
