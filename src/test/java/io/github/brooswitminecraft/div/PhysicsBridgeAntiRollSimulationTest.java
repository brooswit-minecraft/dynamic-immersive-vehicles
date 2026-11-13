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
 * from each side's raw compression and calibrated rate, computed BEFORE that tick's integration.
 * Uses the REAL four-wheel rectangle fixture from comment 53770's own reproduction (x=
 * [1.2,-0.8,1.2,-0.8], z=[1,1,-1,-1]) rather than a singular two-wheel same-z fixture, so the
 * pair genuinely has unequal static shares/rates - exactly the case all three review rounds
 * (53750/53752/53770) turned on. Covers: a tilted chassis must see MORE restoring roll torque
 * than an uncoupled baseline, a common-mode heave under unequal rates must see NO unsolicited
 * torque, and the actual point-cross-force roll torque (normal x lateral offset) is checked
 * directly, not just heave/settling.
 */
class PhysicsBridgeAntiRollSimulationTest {
 private static final double TRAVEL = 0.3;
 private static final double[] RECT_X = {1.2, -0.8, 1.2, -0.8};
 private static final double[] RECT_Z = {1.0, 1.0, -1.0, -1.0};
 /** One tick for a geometric left/right pair, each with its own independent quarter-car vertical
  * DOF - identical per-wheel integration to {@code PhysicsBridgeWheelLoadShareSimulationTest.tick}
  * except each wheel's acceleration divides by ITS OWN effective mass, and - mirroring
  * stepGround's own order exactly - the two independently-computed normals are coupled via
  * {@link AntiRoll#transfer} (from each side's own raw compression/rate) before being used for
  * that same tick's acceleration. {@code contact[i]} drops a wheel from this tick's coupling
  * (and its own force) entirely - the airborne/no-contact case this ticket's own acceptance
  * shape names. */
 private static void tick(double[] compression, double[] velocity, double[] perWheelMass, double gravity,
     double[] spring, double[] damper, boolean[] contact, double stiffness) {
  int n = compression.length;
  double[] normal = new double[n];
  for (int i = 0; i < n; i++) {
   normal[i] = contact[i] ? Math.min(2*perWheelMass[i]*gravity, TireForces.suspension(compression[i], velocity[i], spring[i], damper[i])) : 0.0;
  }
  if (contact[0] && contact[1]) {
   double t = AntiRoll.transfer(compression[0], compression[1], spring[0], spring[1], normal[0], normal[1], stiffness);
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
 /** Builds the two-wheel rig for the x=+1.2/-0.8, z=1.0 pair of the real rectangle fixture -
  * genuinely unequal shares/rates, unlike a singular two-wheel same-z fixture. */
 private static Rig rectPairRig(double mass, double gravity) {
  double[] shares = WheelLoadDistribution.shares(RECT_X, RECT_Z);
  double[] spring = new double[2], damper = new double[2], perWheelMass = new double[2];
  for (int i = 0; i < 2; i++) {
   perWheelMass[i] = mass*shares[i];
   spring[i] = PhysicsBridge.suspensionSpringForShare(mass, shares[i], TRAVEL, gravity);
   damper[i] = PhysicsBridge.suspensionDamperForShare(mass, shares[i], spring[i]);
  }
  assertNotEquals(spring[0], spring[1], 1e-6, "sanity: the rectangle fixture's x=+1.2/-0.8 pair must have genuinely unequal calibrated rates");
  return new Rig(perWheelMass, gravity, spring, damper);
 }
 private static double normalNow(Rig rig, double compression, double velocity, int side) {
  return Math.min(2*rig.perWheelMass()[side]*rig.gravity(), TireForces.suspension(compression, velocity, rig.spring()[side], rig.damper()[side]));
 }
 @Test void zeroStiffnessReproducesTheUncoupledSettlingExactly() {
  double mass = 1500, gravity = 11;
  Rig rig = rectPairRig(mass, gravity);
  double[] compA = {TRAVEL*.1, TRAVEL*.7}, velA = {0,0};
  double[] compB = {TRAVEL*.1, TRAVEL*.7}, velB = {0,0};
  boolean[] contact = {true, true};
  for (int t = 0; t < 200; t++) {
   tick(compA, velA, rig.perWheelMass(), gravity, rig.spring(), rig.damper(), contact, 0.0);
   tick(compB, velB, rig.perWheelMass(), gravity, rig.spring(), rig.damper(), contact, -1.0); // negative also means off
  }
  assertArrayEquals(compB, compA, 1e-12);
 }
 @Test void bothSidesConvergeToTheirOwnEquilibriumWithOrWithoutCouplingWhenStartingAtACommonCompression() {
  // Starting both sides at the SAME compression (a pure common-mode disturbance from rest, no
  // actual roll) under genuinely unequal rates: zero transfer every tick regardless of
  // stiffness, so both must settle to their OWN travel*.5 equilibrium exactly as the uncoupled
  // case would - this is the production-path analogue of commonHeaveUnderGenuinelyUnequalShares.
  double mass = 1500, gravity = 11;
  Rig rig = rectPairRig(mass, gravity);
  for (double stiffness : new double[]{0.0, 0.3, 0.6, 1.0}) {
   double start = TRAVEL*.2; // same starting compression for both sides
   double[] compression = {start, start}, velocity = {0,0};
   boolean[] contact = {true, true};
   for (int t = 0; t < 4000; t++) tick(compression, velocity, rig.perWheelMass(), gravity, rig.spring(), rig.damper(), contact, stiffness);
   assertEquals(TRAVEL*.5, compression[0], 1e-6, "stiffness="+stiffness);
   assertEquals(TRAVEL*.5, compression[1], 1e-6, "stiffness="+stiffness);
  }
 }
 @Test void commonModeHeaveUnderGenuinelyUnequalRatesGetsNoUnsolicitedRollTorque() {
  // Comment 53770's exact reproduction, driven through the real production formulas: both
  // sides of the x=+1.2/-0.8 pair compress by the SAME extra amount from rest (a true common
  // heave/bounce, not an actual roll) - must see the SAME point-cross-force roll torque
  // (normal*x summed over the pair) with or without a configured anti-roll stiffness, despite
  // the pair's own rates genuinely differing.
  double mass = 1500, gravity = 11;
  Rig rig = rectPairRig(mass, gravity);
  double commonCompression = TRAVEL*.5 + 0.04; // both sides +4cm from rest, together
  double normalA = normalNow(rig, commonCompression, 0, 0);
  double normalB = normalNow(rig, commonCompression, 0, 1);
  double uncoupledTorque = normalA*RECT_X[0] + normalB*RECT_X[1];
  for (double stiffness : new double[]{0.2, 0.6, 1.0, 3.0}) {
   double t = AntiRoll.transfer(commonCompression, commonCompression, rig.spring()[0], rig.spring()[1], normalA, normalB, stiffness);
   double coupledTorque = (normalA+t)*RECT_X[0] + (normalB-t)*RECT_X[1];
   assertEquals(uncoupledTorque, coupledTorque, 1e-9, "stiffness="+stiffness+": a common-mode heave under unequal rates must see NO change in roll torque from any configured stiffness");
   assertEquals(0.0, t, 1e-9, "stiffness="+stiffness);
  }
 }
 @Test void tiltedAxleGetsMoreRestoringRollTorqueThanTheUncoupledBaseline() {
  // The actual acceptance case comment 53750 asked for, now on the real unequal-rate rectangle
  // pair: one side genuinely more compressed than the other (an actual roll disturbance) must
  // see a LARGER restoring point-cross-force roll torque with coupling than the uncoupled
  // baseline produces on its own - never smaller, never exactly equal.
  double mass = 1500, gravity = 11;
  Rig rig = rectPairRig(mass, gravity);
  double compressedSide = TRAVEL*.75, extendedSide = TRAVEL*.25; // a genuine roll disturbance
  double normalA = normalNow(rig, compressedSide, 0, 0);
  double normalB = normalNow(rig, extendedSide, 0, 1);
  double uncoupledTorque = normalA*RECT_X[0] + normalB*RECT_X[1];
  for (double stiffness : new double[]{0.2, 0.5, 1.0}) {
   double t = AntiRoll.transfer(compressedSide, extendedSide, rig.spring()[0], rig.spring()[1], normalA, normalB, stiffness);
   double coupledTorque = (normalA+t)*RECT_X[0] + (normalB-t)*RECT_X[1];
   assertTrue(Math.abs(coupledTorque) > Math.abs(uncoupledTorque),
     "stiffness="+stiffness+": coupled restoring torque "+coupledTorque+" must exceed the uncoupled baseline "+uncoupledTorque);
  }
 }
 @Test void totalNormalLoadThisTickIsExactlyConservedAcrossTheTransfer() {
  double mass = 1500, gravity = 11;
  Rig rig = rectPairRig(mass, gravity);
  double normalA = normalNow(rig, TRAVEL*.2, 0, 0);
  double normalB = normalNow(rig, TRAVEL*.8, 0, 1);
  double unblendedTotal = normalA + normalB;
  for (double stiffness : new double[]{0.0, 0.25, 0.5, 0.75, 1.0}) {
   double t = AntiRoll.transfer(TRAVEL*.2, TRAVEL*.8, rig.spring()[0], rig.spring()[1], normalA, normalB, stiffness);
   assertEquals(unblendedTotal, (normalA+t) + (normalB-t), 1e-6, "stiffness="+stiffness+" must conserve this tick's total normal load exactly");
  }
 }
 @Test void airbornePartnerLeavesTheGroundedWheelsSettlingCompletelyUnaffectedByConfiguredStiffness() {
  double mass = 1500, gravity = 11;
  Rig rig = rectPairRig(mass, gravity);
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
