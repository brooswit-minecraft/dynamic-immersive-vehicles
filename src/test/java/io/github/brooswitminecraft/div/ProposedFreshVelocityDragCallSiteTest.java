package io.github.brooswitminecraft.div;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MINECRAFT-545/547, John's comment 54703: tests for the UNWIRED call-site mirror
 * {@link ProposedFreshVelocityDragCallSite} - "tests reproducing caller behavior." Not called
 * from any production path.
 */
class ProposedFreshVelocityDragCallSiteTest {
 private static final double DT = 0.05;

 @Test void whenDragIsWithinBoundTheCombinedForceExactlyMatchesBuoyantForcesOwnUnboundedSum() {
  // BuoyantForces.apply's own real combination is simply buoyancyOnlyForce.add(dragForce) (see
  // its own `force.add(q.transform(...))` line). When drag is well within bound, this design
  // must reproduce that EXACT same combined vector - the bound must be inert, not merely
  // "close," whenever it does not actually trigger.
  double mass = 400.0;
  MassData massData = TestMassData.solidBox(mass, new Vector3d(2.0, 1.0, 4.0));
  Vector3d arm = new Vector3d(1.5, -0.8, 3.0);
  Vector3d buoyancy = new Vector3d(0, 50_000.0, 0); // large upward buoyancy, deliberately unrelated to drag's own bound
  Vector3d drag = new Vector3d(0, -0.5, 0); // tiny, certainly within bound
  Vector3d linearVelocity = new Vector3d(0, 43.90, 0);
  Vector3d angularVelocity = new Vector3d(0, 0, 0);

  Vector3d combined = ProposedFreshVelocityDragCallSite.combinedFloatForce(
    massData, arm, buoyancy, drag, linearVelocity, angularVelocity, DT);

  Vector3d expectedUnbounded = new Vector3d(buoyancy).add(drag);
  assertEquals(0, new Vector3d(expectedUnbounded).sub(combined).length(), 1e-9,
    "when drag is within bound, the combined force must exactly match BuoyantForces.apply's own unbounded buoyancy+drag sum");
 }

 @Test void whenDragExceedsBoundBuoyancyIsStillPassedThroughExactlyUnchanged() {
  // The bound must act ONLY on drag - buoyancy, however large, must never be scaled/capped by
  // the drag budget. Confirmed by subtracting the (separately recomputed) bounded drag from
  // the combined result and checking what remains matches buoyancy exactly.
  double mass = 400.0;
  MassData massData = TestMassData.solidBox(mass, new Vector3d(2.0, 1.0, 4.0));
  Vector3d arm = new Vector3d(1.5, -0.8, 3.0);
  Vector3d buoyancy = new Vector3d(0, 999_999.0, 0); // deliberately huge, must pass through untouched
  Vector3d drag = new Vector3d(0, -2_000_000.0, 0); // deliberately far past its own bound
  Vector3d linearVelocity = new Vector3d(0, 43.90, 0);
  Vector3d angularVelocity = new Vector3d(0, 0, 0);

  Vector3d combined = ProposedFreshVelocityDragCallSite.combinedFloatForce(
    massData, arm, buoyancy, drag, linearVelocity, angularVelocity, DT);
  Vector3d boundedDragAlone = ProposedBoundedDragIntegration.boundedDragForce(
    massData, arm, drag, linearVelocity, angularVelocity, DT);

  assertTrue(boundedDragAlone.length() < drag.length(), "sanity check: drag must actually have been clamped for this test to be meaningful");
  Vector3d recoveredBuoyancy = new Vector3d(combined).sub(boundedDragAlone);
  assertEquals(0, new Vector3d(buoyancy).sub(recoveredBuoyancy).length(), 1e-6,
    "buoyancy must pass through completely unchanged - only drag may ever be scaled by this design");
 }

 @Test void theCombinedImpulseAppliedInOneShotCanStillAddEnergyEvenWhenDragAloneIsBounded() {
  // 54703's own OPEN cross-term point, made concrete: bounding drag in isolation does NOT
  // prove the resulting COMBINED (buoyancy+bounded-drag) impulse, applied as ONE impulse
  // exactly as BuoyantForces.apply really does, stays non-energy-adding - because buoyancy's
  // own simultaneous contribution to that SAME impulse is not accounted for by drag's own
  // bound. This test demonstrates the gap exists (not that it is resolved): a large enough
  // buoyancy contribution, combined with an already-bounded drag, can still produce a net
  // energy-adding impulse when measured as the one real combined application.
  double mass = 400.0;
  Vector3d halfExtents = new Vector3d(2.0, 1.0, 4.0);
  MassData massData = TestMassData.solidBox(mass, halfExtents);
  Vector3d arm = new Vector3d(1.5, -0.8, 3.0);
  Vector3d linearVelocity = new Vector3d(0, 43.90, 0);
  Vector3d angularVelocity = new Vector3d(0, 0, 0);
  Vector3d drag = new Vector3d(0, -2_000_000.0, 0); // will be clamped to the non-reversing point ALONE

  Vector3d boundedDragAlone = ProposedBoundedDragIntegration.boundedDragForce(
    massData, arm, drag, linearVelocity, angularVelocity, DT);
  // A large buoyancy contribution in the SAME direction as the (already-bounded) drag - were
  // buoyancy absent, the combined impulse would be exactly the non-reversing point; WITH it,
  // the combined impulse pushes well past that point.
  Vector3d buoyancy = new Vector3d(boundedDragAlone).mul(5.0);

  Vector3d combined = ProposedFreshVelocityDragCallSite.combinedFloatForce(
    massData, arm, buoyancy, drag, linearVelocity, angularVelocity, DT);

  SequentialImpulseEnergyLedger ledger = new SequentialImpulseEnergyLedger(massData, linearVelocity, angularVelocity);
  Vector3d direction = new Vector3d(combined).normalize();
  double energyChange = ledger.applyImpulseAndMeasureEnergyChange(arm, direction, combined.length() * DT);

  assertTrue(energyChange > 0,
    "bounding drag alone does not prevent the real combined (buoyancy+drag) impulse from adding energy - the cross-term gap 54703 named is real, not merely theoretical");
 }
}
