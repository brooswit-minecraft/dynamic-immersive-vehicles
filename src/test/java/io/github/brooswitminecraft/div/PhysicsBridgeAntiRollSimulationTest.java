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
 * already makes) rather than {@code PhysicsBridgeWheelLoadShareSimulationTest}'s single shared
 * chassis-heave DOF. A shared-heave DOF cannot exercise anti-roll at all: with one acceleration
 * applied identically to every wheel, an initial left/right compression gap can never change
 * regardless of any coupling - the per-corner DOF below is what actually lets the two sides
 * evolve differently, and therefore lets {@link AntiRoll#blendedNormalA}'s redistribution
 * actually do something observable. The per-tick ORDER mirrors {@code stepGround} exactly:
 * compute every wheel's own independent suspension normal first, THEN blend a geometrically-
 * paired left/right pair toward their shared average - exactly once per tick, before that normal
 * feeds back into the next tick's integration.
 */
class PhysicsBridgeAntiRollSimulationTest {
 private static final double TRAVEL = 0.3;
 /** One tick for a geometric left/right pair, each with its own independent quarter-car vertical
  * DOF (own velocity/compression, own effective mass {@code perWheelMass}) - identical per-wheel
  * integration to {@code PhysicsBridgeWheelLoadShareSimulationTest.tick} except (1) each wheel's
  * acceleration divides by ITS OWN effective mass rather than one shared total, so the two sides
  * can actually diverge/converge independently, and (2) the two independently-computed normals
  * are blended via {@link AntiRoll#blendedNormalA} before being used for that same tick's
  * acceleration - exactly mirroring stepGround's own "Phase 1 computes every WheelHit.normal(),
  * then the anti-roll pass replaces them, THEN that normal is applied" order. {@code contact[i]}
  * lets a wheel be dropped from this tick's coupling (and its own force) entirely - the
  * airborne/no-contact case this ticket's own acceptance shape names - without needing an actual
  * raycast MISS to reproduce it. The vehicle-wide safety cap step uses is {@code 2*perWheelMass*
  * gravity} (this symmetric pair's own total weight), matching stepGround's real {@code
  * mass*gravity} cap applied per wheel. */
 private static void tick(double[] compression, double[] velocity, double perWheelMass, double gravity,
     double[] spring, double[] damper, boolean[] contact, double stiffness) {
  int n = compression.length;
  double[] normal = new double[n];
  for (int i = 0; i < n; i++) {
   normal[i] = contact[i] ? Math.min(2*perWheelMass*gravity, TireForces.suspension(compression[i], velocity[i], spring[i], damper[i])) : 0.0;
  }
  if (contact[0] && contact[1]) {
   double a = normal[0], b = normal[1];
   normal[0] = AntiRoll.blendedNormalA(a, b, stiffness);
   normal[1] = AntiRoll.blendedNormalA(b, a, stiffness);
  }
  for (int i = 0; i < n; i++) {
   if (!contact[i]) continue;
   double acceleration = -gravity + normal[i]/perWheelMass;
   velocity[i] = velocity[i] + PhysicsBridge.DT*acceleration;
   compression[i] = compression[i] - PhysicsBridge.DT*velocity[i];
  }
 }
 private record Rig(double perWheelMass, double gravity, double[] spring, double[] damper) {}
 private static Rig symmetricPair(double mass, double gravity) {
  double[] loadShares = WheelLoadDistribution.shares(new double[]{1.0,-1.0}, new double[]{0.0,0.0});
  double spring0 = PhysicsBridge.suspensionSpringForShare(mass, loadShares[0], TRAVEL, gravity);
  double damper0 = PhysicsBridge.suspensionDamperForShare(mass, loadShares[0], spring0);
  return new Rig(mass*loadShares[0], gravity, new double[]{spring0,spring0}, new double[]{damper0,damper0});
 }
 private static double[] settlePair(double mass, double gravity, double initialCompressionA, double initialCompressionB,
     double stiffness, int ticks) {
  Rig rig = symmetricPair(mass, gravity);
  double[] compression = {initialCompressionA, initialCompressionB};
  double[] velocity = {0, 0};
  boolean[] contact = {true, true};
  for (int t = 0; t < ticks; t++) tick(compression, velocity, rig.perWheelMass(), rig.gravity(), rig.spring(), rig.damper(), contact, stiffness);
  return compression;
 }
 @Test void zeroStiffnessReproducesTheUncoupledSettlingExactly() {
  // Default/off (stiffness==0) must be BYTE-for-byte (to ordinary floating-point tolerance) what
  // the same quarter-car integration produces with no blend at all - no behavior change for the
  // documented default, at the full simulation level, not just AntiRollTest's unit level.
  double mass = 1500, gravity = 11;
  double[] offExplicit = settlePair(mass, gravity, TRAVEL*.1, TRAVEL*.7, 0.0, 200);
  double[] offNegative = settlePair(mass, gravity, TRAVEL*.1, TRAVEL*.7, -1.0, 200); // negative also means off
  assertArrayEquals(offExplicit, offNegative, 1e-12);
 }
 @Test void partialStiffnessStillConvergesToTheSameSymmetricEquilibriumAsUncoupled() {
  // This pair's own static load share is symmetric, so with or without a PARTIAL anti-roll
  // coupling both sides must still settle to the SAME long-run ride-height equilibrium
  // (travel*.5) - coupling only changes the transient path, never the eventual resting load/
  // compression for a layout that was already symmetric to begin with.
  double mass = 1500, gravity = 11;
  for (double stiffness : new double[]{0.0, 0.3, 0.6, 0.9}) {
   double[] settled = settlePair(mass, gravity, TRAVEL*.1, TRAVEL*.7, stiffness, 4000);
   assertEquals(TRAVEL*.5, settled[0], 1e-6, "stiffness="+stiffness);
   assertEquals(TRAVEL*.5, settled[1], 1e-6, "stiffness="+stiffness);
  }
 }
 @Test void fullyRigidStiffnessConvergesTheAverageButFreezesTheInitialGapRatherThanClosingIt() {
  // Documented nuance (see AntiRoll's own class doc: this couples LOAD, not travel): at exactly
  // 1.0 the pair's normals are forced EXACTLY equal from the very first tick, which - given both
  // sides already share the SAME calibrated spring/damper/effective-mass - makes their
  // acceleration (and therefore velocity) identical forever after. The two compressions are then
  // just the pair's own AVERAGE (which still converges to the ordinary travel*.5 ride-height
  // equilibrium, exactly as an uncoupled single wheel would) plus/minus a fixed offset equal to
  // HALF the original gap - the gap itself is frozen at whatever it was the first coupled tick,
  // never closed, even once the average has fully settled. This is intentional, conservation-
  // preserving behavior (every bit of load difference is eliminated every tick, exactly as
  // documented), not a bug - "conserved suspension LOAD" is the ticket's own stated contract,
  // not "equalized travel".
  double mass = 1500, gravity = 11;
  double initialA = TRAVEL*.1, initialB = TRAVEL*.7;
  double halfGap = (initialB - initialA) / 2.0;
  double[] settled = settlePair(mass, gravity, initialA, initialB, 1.0, 4000);
  assertEquals(TRAVEL*.5 - halfGap, settled[0], 1e-6);
  assertEquals(TRAVEL*.5 + halfGap, settled[1], 1e-6);
  assertEquals(initialB - initialA, settled[1] - settled[0], 1e-9, "the original gap must be exactly preserved, never closed, under a fully-rigid coupling");
 }
 @Test void totalNormalLoadThisTickIsExactlyConservedAcrossTheBlend() {
  double mass = 1500, gravity = 11;
  Rig rig = symmetricPair(mass, gravity);
  double normalA = Math.min(2*rig.perWheelMass()*gravity, TireForces.suspension(TRAVEL*.2, 0, rig.spring()[0], rig.damper()[0]));
  double normalB = Math.min(2*rig.perWheelMass()*gravity, TireForces.suspension(TRAVEL*.8, 0, rig.spring()[1], rig.damper()[1]));
  double unblendedTotal = normalA + normalB;
  for (double stiffness : new double[]{0.0, 0.25, 0.5, 0.75, 1.0}) {
   double blendedA = AntiRoll.blendedNormalA(normalA, normalB, stiffness);
   double blendedB = AntiRoll.blendedNormalA(normalB, normalA, stiffness);
   assertEquals(unblendedTotal, blendedA + blendedB, 1e-6, "stiffness="+stiffness+" must conserve this tick's total normal load exactly");
  }
 }
 @Test void airbornePartnerLeavesTheGroundedWheelsSettlingCompletelyUnaffectedByConfiguredStiffness() {
  double mass = 1500, gravity = 11;
  Rig rig = symmetricPair(mass, gravity);
  boolean[] contact = {true, false}; // partner (index 1) is airborne this whole run
  double[] compressionHighStiffness = {TRAVEL*.2, 0}, velocityHighStiffness = {0, 0};
  double[] compressionNoStiffness = {TRAVEL*.2, 0}, velocityNoStiffness = {0, 0};
  for (int t = 0; t < 200; t++) {
   tick(compressionHighStiffness, velocityHighStiffness, rig.perWheelMass(), rig.gravity(), rig.spring(), rig.damper(), contact, 1.0);
   tick(compressionNoStiffness, velocityNoStiffness, rig.perWheelMass(), rig.gravity(), rig.spring(), rig.damper(), contact, 0.0);
  }
  assertEquals(compressionNoStiffness[0], compressionHighStiffness[0], 1e-12,
    "a configured (even fully-rigid) anti-roll coupling must not change the grounded wheel's own settling at all when its paired partner has no contact this tick");
 }
}
