package io.github.brooswitminecraft.div.experimental;
import io.github.brooswitminecraft.div.TireForces;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for the accumulation bug review found in {@link MultiContactFrictionSolver}
 * (comment 53022), and for the weaker version of this test review then found did not actually
 * detect it (comment 53025): repeated SEPARATE one-pass calls each start a fresh local
 * accumulator (it is a local variable inside {@code solveFriction}), so they never exercise the
 * bug at all - it only appears WITHIN one multi-pass call, and only on an EVEN number of passes
 * (each pass fully undoes the previous one, so the parity of the iteration count determines
 * whether the final state is "fully cancelled" (odd) or "back to the original slip" (even)).
 *
 * <p>This version starts from the SAME nonzero slip independently for each iteration count
 * (1, 2, 4, 8) - not chained across calls - and additionally reproduces the OLD (pre-53022)
 * buggy accumulation logic directly in this test so the fixed solver's behavior can be compared
 * against it, proving this test actually discriminates: the buggy version returns to (almost)
 * the ORIGINAL slip for every EVEN iteration count tested here, while the real, fixed
 * {@link MultiContactFrictionSolver} converges to (and stays at) near-zero slip for every count.
 */
class MultiContactFrictionSolverConvergenceTest {
 private static final double MASS = 350.0, DT = 0.05, FRICTION = 1.0, NORMAL = 10000; // generous, never saturates
 private static final Vector3d INERTIA = new Vector3d(100,100,100); // irrelevant with one contact at the COM
 private static final Vector3d POINT_AT_COM = new Vector3d(0,0,0); // zero moment arm - no rotational coupling at all
 private static final Vector3d FORWARD = new Vector3d(0,0,1), RIGHT = new Vector3d(1,0,0);
 private static final Quaterniond IDENTITY = new Quaterniond();

 /** The OLD (pre-53022) buggy accumulation, reproduced here only to prove this test
  * discriminates - not present in {@link MultiContactFrictionSolver} itself anymore. */
 private static double oldBuggyFinalSideSpeed(int iterations, double initialSideSpeed) {
  double v=initialSideSpeed, accLat=0;
  for (int pass=0; pass<iterations; pass++) {
   double effectiveMass=MultiContactFrictionSolver.contactLateralEffectiveMass(MASS,POINT_AT_COM,RIGHT,IDENTITY,INERTIA);
   double target=-v*effectiveMass/DT;
   var tire=TireForces.friction(0,target,NORMAL,FRICTION);
   double delta=tire.lateral()-accLat;
   accLat=tire.lateral();
   v += delta*DT/MASS;
  }
  return v;
 }

 private static double fixedFinalSideSpeed(int iterations, double initialSideSpeed) {
  Vector3d velocity=new Vector3d(RIGHT).mul(initialSideSpeed);
  Vector3d angular=new Vector3d(0,0,0);
  var contacts=new MultiContactFrictionSolver.FrictionContact[]{new MultiContactFrictionSolver.FrictionContact(POINT_AT_COM,FORWARD,RIGHT,NORMAL,1.0)};
  MultiContactFrictionSolver.solveFriction(contacts,MASS,INERTIA,IDENTITY,IDENTITY,velocity,angular,0.0,0,0,FRICTION,1.0,DT,iterations);
  return velocity.dot(RIGHT);
 }

 @Test void thisTestActuallyDiscriminates_oldBuggyLogicFailsOnEvenIterationCounts() {
  // Proves the regression below is not vacuous: reproducing the OLD accumulation exactly as it
  // was before 53022's fix, for an EVEN number of passes, returns to (almost) the ORIGINAL
  // slip - the bug 53025 said the previous version of this test failed to catch.
  for (int n : new int[]{2,4,8}) {
   double remaining=oldBuggyFinalSideSpeed(n, 1.0);
   assertTrue(Math.abs(remaining-1.0) < 1e-6,
     "old buggy logic at "+n+" passes should return to the ORIGINAL slip (1.0), got "+remaining+" - if this fails, the 'old buggy' reproduction above no longer matches what was actually fixed");
  }
 }

 @Test void fixedSolverConvergesForEveryIterationCountStartingFromTheSameSlipIndependently() {
  for (int n : new int[]{1,2,4,8}) {
   double remaining=fixedFinalSideSpeed(n, 1.0);
   assertTrue(Math.abs(remaining) < 1e-6,
     "fixed solver at "+n+" passes (starting fresh from sideSpeed=1.0, not chained) should converge to near-zero slip - got "+remaining);
  }
 }

 @Test void convergedStateIsIdempotentUnderFurtherSolves() {
  Vector3d velocity=new Vector3d(RIGHT).mul(1.0);
  Vector3d angular=new Vector3d(0,0,0);
  var contacts=new MultiContactFrictionSolver.FrictionContact[]{new MultiContactFrictionSolver.FrictionContact(POINT_AT_COM,FORWARD,RIGHT,NORMAL,1.0)};
  MultiContactFrictionSolver.solveFriction(contacts,MASS,INERTIA,IDENTITY,IDENTITY,velocity,angular,0.0,0,0,FRICTION,1.0,DT,4);
  double afterFirst=velocity.dot(RIGHT);
  MultiContactFrictionSolver.solveFriction(contacts,MASS,INERTIA,IDENTITY,IDENTITY,velocity,angular,0.0,0,0,FRICTION,1.0,DT,4);
  double afterSecond=velocity.dot(RIGHT);
  assertEquals(afterFirst, afterSecond, 1e-9, "re-solving an already-at-rest contact must not reintroduce slip");
 }
}
