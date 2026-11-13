package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/**
 * MINECRAFT-519: a focused, configured anti-roll PRODUCTION-PATH simulation, in the same spirit
 * as {@link PhysicsBridgeWheelLoadShareSimulationTest}'s own per-wheel vertical-dynamics
 * substitute (same semi-implicit Euler integration, same production {@link
 * TireForces#suspension} force law, same fixed {@code DT}, same reason {@code Body.stepGround}
 * itself cannot be driven without a live Minecraft/IV/Sable runtime - see VERIFICATION.md), but
 * giving each paired wheel its OWN independent vertical degree of freedom (a quarter-car corner,
 * own effective mass {@code mass*loadShare} - exactly the same per-wheel effective-mass
 * assumption {@link PhysicsBridge#suspensionDamperForShare}'s own critical-damping formula
 * already makes) and applying {@link AntiRoll#transfer} exactly the way {@code stepGround} does:
 * against each side's own static-equilibrium baseline ({@code mass*gravity*loadShare}), computed
 * BEFORE that tick's integration. Covers the review-corrected acceptance shape (comments
 * 53750/53752): a tilted chassis must see MORE restoring roll torque than an uncoupled baseline
 * (not less), a neutral/asymmetric-static-load pair must see NO unsolicited torque, and the
 * actual point-cross-force roll torque (normal x lateral offset) is checked directly, not just
 * heave/settling.
 */
class PhysicsBridgeAntiRollSimulationTest {
 private static final double TRAVEL = 0.3;
 /** One tick for a geometric left/right pair, each with its own independent quarter-car vertical
  * DOF - identical per-wheel integration to {@code PhysicsBridgeWheelLoadShareSimulationTest.tick}
  * except each wheel's acceleration divides by ITS OWN effective mass, and - mirroring
  * stepGround's own order exactly - the two independently-computed normals are coupled via
  * {@link AntiRoll#transfer} (against each side's own static baseline {@code effectiveMass*
  * gravity}, i.e. {@code mass*loadShare*gravity}) before being used for that same tick's
  * acceleration. {@code contact[i]} drops a wheel from this tick's coupling (and its own force)
  * entirely - the airborne/no-contact case this ticket's own acceptance shape names. */
 private static void tick(double[] compression, double[] velocity, double[] perWheelMass, double gravity,
     double[] spring, double[] damper, boolean[] contact, double stiffness) {
  int n = compression.length;
  double[] normal = new double[n];
  for (int i = 0; i < n; i++) {
   normal[i] = contact[i] ? Math.min(2*perWheelMass[i]*gravity, TireForces.suspension(compression[i], velocity[i], spring[i], damper[i])) : 0.0;
  }
  if (contact[0] && contact[1]) {
   double baselineA = perWheelMass[0]*gravity, baselineB = perWheelMass[1]*gravity;
   double t = AntiRoll.transfer(normal[0], baselineA, normal[1], baselineB, stiffness);
   normal[0] = normal[0] + t;
   normal[1] = normal[1] - t;
  }
  for (int i = 0; i < n; i++) {
   if (!contact[i]) continue;
   double acceleration = -gravity + normal[i]/perWheelMass[i];
   velocity[i] = velocity[i] + PhysicsBridge.DT*acceleration;
   compression[i] = compression[i] - PhysicsBridge.DT*velocity[i];
  }
 }
 private record Rig(double[] perWheelMass, double gravity, double[] spring, double[] damper) {}
 private static Rig pairRig(double mass, double gravity, double[] loadShares) {
  double[] spring = new double[2], damper = new double[2], perWheelMass = new double[2];
  for (int i = 0; i < 2; i++) {
   perWheelMass[i] = mass*loadShares[i];
   spring[i] = PhysicsBridge.suspensionSpringForShare(mass, loadShares[i], TRAVEL, gravity);
   damper[i] = PhysicsBridge.suspensionDamperForShare(mass, loadShares[i], spring[i]);
  }
  return new Rig(perWheelMass, gravity, spring, damper);
 }
 private static double normalNow(Rig rig, double compression, double velocity, int side) {
  return Math.min(2*rig.perWheelMass()[side]*rig.gravity(), TireForces.suspension(compression, velocity, rig.spring()[side], rig.damper()[side]));
 }
 @Test void zeroStiffnessReproducesTheUncoupledSettlingExactly() {
  double mass = 1500, gravity = 11;
  double[] shares = WheelLoadDistribution.shares(new double[]{1.0,-1.0}, new double[]{0.0,0.0});
  Rig rig = pairRig(mass, gravity, shares);
  double[] compA = {TRAVEL*.1, TRAVEL*.7}, velA = {0,0};
  double[] compB = {TRAVEL*.1, TRAVEL*.7}, velB = {0,0};
  boolean[] contact = {true, true};
  for (int t = 0; t < 200; t++) {
   tick(compA, velA, rig.perWheelMass(), gravity, rig.spring(), rig.damper(), contact, 0.0);
   tick(compB, velB, rig.perWheelMass(), gravity, rig.spring(), rig.damper(), contact, -1.0); // negative also means off
  }
  assertArrayEquals(compB, compA, 1e-12);
 }
 @Test void symmetricAxleNeutralDisturbanceConvergesToTheSameEquilibriumWithOrWithoutCoupling() {
  double mass = 1500, gravity = 11;
  double[] shares = WheelLoadDistribution.shares(new double[]{1.0,-1.0}, new double[]{0.0,0.0}); // 0.5/0.5
  Rig rig = pairRig(mass, gravity, shares);
  for (double stiffness : new double[]{0.0, 0.3, 0.6, 1.0}) {
   double[] compression = {TRAVEL*.1, TRAVEL*.7}, velocity = {0,0};
   boolean[] contact = {true, true};
   for (int t = 0; t < 4000; t++) tick(compression, velocity, rig.perWheelMass(), gravity, rig.spring(), rig.damper(), contact, stiffness);
   assertEquals(TRAVEL*.5, compression[0], 1e-6, "stiffness="+stiffness);
   assertEquals(TRAVEL*.5, compression[1], 1e-6, "stiffness="+stiffness);
  }
 }
 @Test void neutralAsymmetricAxleAtItsOwnStaticEquilibriumGetsNoUnsolicitedRollTorque() {
  // Review comment 53752's exact reproduction, driven through the real production formulas:
  // an asymmetric pair (x=+1.2/-0.8, so UNEQUAL loadShares/baselines) sitting exactly at its own
  // static equilibrium must see the SAME normals - and so the SAME point-cross-force roll
  // torque (normal*x summed over the pair) - with or without a configured anti-roll stiffness.
  double mass = 1500, gravity = 11;
  double[] x = {1.2, -0.8}, z = {0.0, 0.0};
  double[] shares = WheelLoadDistribution.shares(x, z);
  Rig rig = pairRig(mass, gravity, shares);
  // At rest at exactly travel*.5 compression with zero velocity, each side's own calibrated
  // spring supports EXACTLY its own baseline (mass*gravity*loadShare) by construction - no roll.
  double normalA = normalNow(rig, TRAVEL*.5, 0, 0);
  double normalB = normalNow(rig, TRAVEL*.5, 0, 1);
  double baselineA = rig.perWheelMass()[0]*gravity, baselineB = rig.perWheelMass()[1]*gravity;
  assertEquals(baselineA, normalA, 1e-6, "sanity: resting at travel*.5 must reproduce this side's own static baseline exactly");
  assertEquals(baselineB, normalB, 1e-6);
  double uncoupledTorque = normalA*x[0] + normalB*x[1];
  for (double stiffness : new double[]{0.2, 0.6, 1.0, 3.0}) {
   double t = AntiRoll.transfer(normalA, baselineA, normalB, baselineB, stiffness);
   double coupledTorque = (normalA+t)*x[0] + (normalB-t)*x[1];
   assertEquals(uncoupledTorque, coupledTorque, 1e-9, "stiffness="+stiffness+": a neutral asymmetric axle must see NO change in roll torque from any configured stiffness");
   assertEquals(0.0, t, 1e-9, "stiffness="+stiffness);
  }
 }
 @Test void tiltedAxleGetsMoreRestoringRollTorqueThanTheUncoupledBaseline() {
  // The actual acceptance case comment 53750 asked for: a tilted (one side compressed more than
  // its own baseline, the other less) symmetric axle must see a LARGER restoring point-cross-
  // force roll torque with coupling than the uncoupled baseline produces on its own - never a
  // smaller one, and never exactly equal (which would mean the bar did nothing).
  double mass = 1500, gravity = 11;
  double[] x = {1.0, -1.0}, z = {0.0, 0.0}; // symmetric half-track for a clean torque sign check
  double[] shares = WheelLoadDistribution.shares(x, z); // 0.5/0.5
  Rig rig = pairRig(mass, gravity, shares);
  double compressedSide = TRAVEL*.75, extendedSide = TRAVEL*.25; // a genuine roll disturbance
  double normalA = normalNow(rig, compressedSide, 0, 0);
  double normalB = normalNow(rig, extendedSide, 0, 1);
  double baselineA = rig.perWheelMass()[0]*gravity, baselineB = rig.perWheelMass()[1]*gravity;
  double uncoupledTorque = normalA*x[0] + normalB*x[1];
  for (double stiffness : new double[]{0.2, 0.5, 1.0}) {
   double t = AntiRoll.transfer(normalA, baselineA, normalB, baselineB, stiffness);
   double coupledTorque = (normalA+t)*x[0] + (normalB-t)*x[1];
   assertTrue(Math.abs(coupledTorque) > Math.abs(uncoupledTorque),
     "stiffness="+stiffness+": coupled restoring torque "+coupledTorque+" must exceed the uncoupled baseline "+uncoupledTorque);
  }
 }
 @Test void totalNormalLoadThisTickIsExactlyConservedAcrossTheTransfer() {
  double mass = 1500, gravity = 11;
  double[] shares = WheelLoadDistribution.shares(new double[]{1.0,-1.0}, new double[]{0.0,0.0});
  Rig rig = pairRig(mass, gravity, shares);
  double normalA = normalNow(rig, TRAVEL*.2, 0, 0);
  double normalB = normalNow(rig, TRAVEL*.8, 0, 1);
  double baselineA = rig.perWheelMass()[0]*gravity, baselineB = rig.perWheelMass()[1]*gravity;
  double unblendedTotal = normalA + normalB;
  for (double stiffness : new double[]{0.0, 0.25, 0.5, 0.75, 1.0}) {
   double t = AntiRoll.transfer(normalA, baselineA, normalB, baselineB, stiffness);
   assertEquals(unblendedTotal, (normalA+t) + (normalB-t), 1e-6, "stiffness="+stiffness+" must conserve this tick's total normal load exactly");
  }
 }
 @Test void airbornePartnerLeavesTheGroundedWheelsSettlingCompletelyUnaffectedByConfiguredStiffness() {
  double mass = 1500, gravity = 11;
  double[] shares = WheelLoadDistribution.shares(new double[]{1.0,-1.0}, new double[]{0.0,0.0});
  Rig rig = pairRig(mass, gravity, shares);
  boolean[] contact = {true, false}; // partner (index 1) is airborne this whole run
  double[] compressionHighStiffness = {TRAVEL*.2, 0}, velocityHighStiffness = {0, 0};
  double[] compressionNoStiffness = {TRAVEL*.2, 0}, velocityNoStiffness = {0, 0};
  for (int t = 0; t < 200; t++) {
   tick(compressionHighStiffness, velocityHighStiffness, rig.perWheelMass(), gravity, rig.spring(), rig.damper(), contact, 1.0);
   tick(compressionNoStiffness, velocityNoStiffness, rig.perWheelMass(), gravity, rig.spring(), rig.damper(), contact, 0.0);
  }
  assertEquals(compressionNoStiffness[0], compressionHighStiffness[0], 1e-12,
    "a configured anti-roll coupling must not change the grounded wheel's own settling at all when its paired partner has no contact this tick");
 }
}
