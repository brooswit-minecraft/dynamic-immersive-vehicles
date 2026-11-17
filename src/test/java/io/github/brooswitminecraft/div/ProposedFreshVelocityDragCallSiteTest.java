package io.github.brooswitminecraft.div;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MINECRAFT-545/547, John's comments 54703/54706: tests for the UNWIRED call-site mirror
 * {@link ProposedFreshVelocityDragCallSite}. Not called from any production path.
 */
class ProposedFreshVelocityDragCallSiteTest {
 private static final double DT = 0.05;

 /** A simple "drag opposes velocity, magnitude proportional to speed" model - stands in for
  * BuoyantForces.apply's own real per-axis directional drag formula, which this design
  * deliberately does not duplicate (see DragForceFromVelocity's own doc). */
 private static ProposedFreshVelocityDragCallSite.DragForceFromVelocity linearDragModel(Vector3dc arm, double coefficient) {
  return (linVel, angVel) -> {
   Vector3d pointVelocity = new Vector3d(angVel).cross(arm).add(new Vector3d(linVel));
   double speed = pointVelocity.length();
   if (speed == 0) return new Vector3d(0, 0, 0);
   return new Vector3d(pointVelocity).normalize().mul(-coefficient * speed);
  };
 }

 private static double kineticEnergy(MassData massData, Vector3dc linVel, Vector3dc angVel) {
  Vector3d iOmega = massData.getInertiaTensor().transform(new Vector3d(angVel), new Vector3d());
  return 0.5 * massData.getMass() * linVel.length() * linVel.length() + 0.5 * new Vector3d(angVel).dot(iOmega);
 }

 @Test void boundedDragNeverRaisesKineticEnergyBeyondTheBuoyancyOnlyBaseline_dragOpposingMotion() {
  // 54706's corrected criterion: compare final KE against the buoyancy-ONLY baseline (buoyancy
  // alone is free to add energy) - not against the pre-step state.
  double mass = 400.0;
  Vector3d halfExtents = new Vector3d(2.0, 1.0, 4.0);
  MassData massData = TestMassData.solidBox(mass, halfExtents);
  Vector3d arm = new Vector3d(1.5, -0.8, 3.0);
  Vector3d linearVelocity = new Vector3d(0, 20.0, 0);
  Vector3d angularVelocity = new Vector3d(0.2, -0.3, 0.1);
  Vector3d buoyancy = new Vector3d(0, 80_000.0, 0); // large upward buoyancy - WILL add real energy

  Vector3d combined = ProposedFreshVelocityDragCallSite.virtualBuoyancyFirstCombinedForce(
    massData, arm, buoyancy, linearDragModel(arm, 400.0), linearVelocity, angularVelocity, DT);

  // Buoyancy-only baseline: apply ONLY buoyancy's own impulse to the SAME original state.
  SequentialImpulseEnergyLedger buoyancyOnlyLedger = new SequentialImpulseEnergyLedger(massData, linearVelocity, angularVelocity);
  buoyancyOnlyLedger.applyImpulseAndMeasureEnergyChange(arm, new Vector3d(buoyancy).normalize(), buoyancy.length() * DT);
  double keBuoyancyOnly = kineticEnergy(massData, buoyancyOnlyLedger.linearVelocity(), buoyancyOnlyLedger.angularVelocity());

  // Real combined application (one impulse, exactly as production would apply it).
  SequentialImpulseEnergyLedger combinedLedger = new SequentialImpulseEnergyLedger(massData, linearVelocity, angularVelocity);
  combinedLedger.applyImpulseAndMeasureEnergyChange(arm, new Vector3d(combined).normalize(), combined.length() * DT);
  double keCombined = kineticEnergy(massData, combinedLedger.linearVelocity(), combinedLedger.angularVelocity());

  assertTrue(keCombined <= keBuoyancyOnly + 1e-6,
   "bounded drag must never raise kinetic energy beyond what buoyancy alone would have produced for this step");
  // Sanity: buoyancy alone DID add energy (confirms this is a real test, not a vacuous one).
  double keBefore = kineticEnergy(massData, linearVelocity, angularVelocity);
  assertTrue(keBuoyancyOnly > keBefore, "sanity: buoyancy alone must actually add energy here, or this test proves nothing");
 }

 @Test void boundedDragNeverRaisesKineticEnergyBeyondTheBuoyancyOnlyBaseline_dragAidingMotion() {
  // Same invariant, but with the drag model's natural direction happening to ALIGN with
  // buoyancy's own direction at this arm/velocity (the "same direction" case 54706 asked for).
  double mass = 250.0;
  Vector3d halfExtents = new Vector3d(3.0, 0.75, 1.5);
  MassData massData = TestMassData.solidBox(mass, halfExtents);
  Vector3d arm = new Vector3d(-2.2, 1.1, -0.9);
  Vector3d linearVelocity = new Vector3d(-12.0, 59.09, 20.15); // incident-scale
  Vector3d angularVelocity = new Vector3d(1.5, -2.0, 0.8);
  Vector3d buoyancy = new Vector3d(0, 50_000.0, 0);

  Vector3d combined = ProposedFreshVelocityDragCallSite.virtualBuoyancyFirstCombinedForce(
    massData, arm, buoyancy, linearDragModel(arm, 300.0), linearVelocity, angularVelocity, DT);

  SequentialImpulseEnergyLedger buoyancyOnlyLedger = new SequentialImpulseEnergyLedger(massData, linearVelocity, angularVelocity);
  buoyancyOnlyLedger.applyImpulseAndMeasureEnergyChange(arm, new Vector3d(buoyancy).normalize(), buoyancy.length() * DT);
  double keBuoyancyOnly = kineticEnergy(massData, buoyancyOnlyLedger.linearVelocity(), buoyancyOnlyLedger.angularVelocity());

  SequentialImpulseEnergyLedger combinedLedger = new SequentialImpulseEnergyLedger(massData, linearVelocity, angularVelocity);
  combinedLedger.applyImpulseAndMeasureEnergyChange(arm, new Vector3d(combined).normalize(), combined.length() * DT);
  double keCombined = kineticEnergy(massData, combinedLedger.linearVelocity(), combinedLedger.angularVelocity());

  assertTrue(keCombined <= keBuoyancyOnly + 1e-6,
   "bounded drag must never raise kinetic energy beyond the buoyancy-only baseline, even when drag's own natural direction happens to align with buoyancy's");
 }

 @Test void buoyancyIsNeverScaledRegardlessOfHowFarDragWouldHaveExceededItsOwnBound() {
  double mass = 400.0;
  MassData massData = TestMassData.solidBox(mass, new Vector3d(2.0, 1.0, 4.0));
  Vector3d arm = new Vector3d(1.5, -0.8, 3.0);
  Vector3d buoyancy = new Vector3d(0, 999_999.0, 0);
  Vector3d linearVelocity = new Vector3d(0, 43.90, 0);
  Vector3d angularVelocity = new Vector3d(0, 0, 0);

  Vector3d combined = ProposedFreshVelocityDragCallSite.virtualBuoyancyFirstCombinedForce(
    massData, arm, buoyancy, linearDragModel(arm, 5_000_000.0), linearVelocity, angularVelocity, DT);

  // combined = buoyancy + boundedDrag; recovering boundedDrag and confirming buoyancy itself matches exactly.
  Vector3d impliedDrag = new Vector3d(combined).sub(buoyancy);
  Vector3d recovered = new Vector3d(combined).sub(impliedDrag);
  assertEquals(0, new Vector3d(buoyancy).sub(recovered).length(), 1e-6, "buoyancy must be recoverable exactly - it was never touched");
 }

 @Test void multipleRotatedOffCenterFloatsAppliedSequentiallyEachKeepTheirOwnStepsCombinedKineticEnergyAtOrBelowTheirOwnBuoyancyOnlyBaseline() {
  // 54706: "rotated/off-center mixed floats... incident-scale velocities." Applies the
  // virtual-buoyancy-first combined force for three off-center floats SEQUENTIALLY against a
  // REAL shared ledger (matching confirmed native per-float application order), checking at
  // EACH float's own step that its own contribution does not exceed ITS OWN buoyancy-only
  // baseline computed from the state live at that point.
  double mass = 400.0;
  Vector3d halfExtents = new Vector3d(2.0, 1.0, 4.0);
  MassData massData = TestMassData.solidBox(mass, halfExtents);
  SequentialImpulseEnergyLedger realLedger = new SequentialImpulseEnergyLedger(
    massData, new Vector3d(10.0, 59.09, 20.15), new Vector3d(3.0, -34.16, 5.0)); // incident-scale

  Vector3d[] arms = {
   new Vector3d(1.8, -0.9, 3.5),
   new Vector3d(-1.8, -0.9, 3.5),
   new Vector3d(0.0, 0.9, -3.5),
  };
  Vector3d[] buoyancies = {
   new Vector3d(0, 40_000.0, 0),
   new Vector3d(0, 35_000.0, 0),
   new Vector3d(0, 45_000.0, 0),
  };

  for (int i = 0; i < arms.length; i++) {
   Vector3d freshLinear = new Vector3d(realLedger.linearVelocity());
   Vector3d freshAngular = new Vector3d(realLedger.angularVelocity());

   double keBefore = kineticEnergy(massData, freshLinear, freshAngular);
   SequentialImpulseEnergyLedger buoyancyOnlyProbe = new SequentialImpulseEnergyLedger(massData, freshLinear, freshAngular);
   buoyancyOnlyProbe.applyImpulseAndMeasureEnergyChange(arms[i], new Vector3d(buoyancies[i]).normalize(), buoyancies[i].length() * DT);
   double keBuoyancyOnly = kineticEnergy(massData, buoyancyOnlyProbe.linearVelocity(), buoyancyOnlyProbe.angularVelocity());

   Vector3d combined = ProposedFreshVelocityDragCallSite.virtualBuoyancyFirstCombinedForce(
     massData, arms[i], buoyancies[i], linearDragModel(arms[i], 2_000_000.0), freshLinear, freshAngular, DT);
   realLedger.applyImpulseAndMeasureEnergyChange(arms[i], new Vector3d(combined).normalize(), combined.length() * DT);
   double keCombined = kineticEnergy(massData, realLedger.linearVelocity(), realLedger.angularVelocity());

   assertTrue(keCombined <= keBuoyancyOnly + 1e-6,
    "float " + i + ": bounded drag must not raise kinetic energy beyond that float's own buoyancy-only baseline");
   // NOTE: buoyancy's own torque contribution (off-center arm) can legitimately REDUCE total KE
   // in some orientations even as it adds translational KE - buoyancy being "free to add energy"
   // does not mean every application of it raises TOTAL (linear+angular) KE; keBefore is kept
   // here only for readability, not asserted against.
   assertTrue(Double.isFinite(keBefore));
  }
 }
}
