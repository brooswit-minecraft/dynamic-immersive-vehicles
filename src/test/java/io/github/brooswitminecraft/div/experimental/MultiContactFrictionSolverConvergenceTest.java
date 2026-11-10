package io.github.brooswitminecraft.div.experimental;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for the accumulation bug review found in {@link MultiContactFrictionSolver}
 * (comment 53022): the lateral target is a per-pass INCREMENT on top of what earlier passes
 * already accumulated (the remaining measured slip after their correction), not a one-shot
 * target that should REPLACE the running total - the earlier version did the latter, so each
 * pass's much-smaller "remaining slip" target effectively undid most of the previous pass's
 * correction, oscillating between near-full correction and near-zero correction instead of
 * converging.
 *
 * <p>A single, unconstrained (friction never saturates), non-rotating contact isolates this
 * cleanly: with no rotational coupling at all, one contact's own effective mass for its own
 * slip direction exactly reduces to a textbook single-body case, so correct accumulation must
 * converge to (and then stay at) full slip cancellation - and calling the solver again on an
 * already-converged state must be idempotent (leave it there), not reintroduce slip.
 */
class MultiContactFrictionSolverConvergenceTest {
 private static final double MASS = 350.0, DT = 0.05, FRICTION = 1.0, NORMAL = 10000; // generous, never saturates
 private static final Vector3d INERTIA = new Vector3d(100,100,100); // irrelevant with one contact at the COM
 private static final Vector3d POINT_AT_COM = new Vector3d(0,0,0); // zero moment arm - no rotational coupling at all
 private static final Vector3d FORWARD = new Vector3d(0,0,1), RIGHT = new Vector3d(1,0,0);
 private static final Quaterniond IDENTITY = new Quaterniond();

 @Test void onePassOnAnUnconstrainedNonRotatingContactCancelsMostOfTheSlip() {
  Vector3d velocity=new Vector3d(0,0,0).add(new Vector3d(RIGHT).mul(1.0)); // 1 m/s pure lateral slip
  Vector3d angular=new Vector3d(0,0,0);
  var contacts=new MultiContactFrictionSolver.FrictionContact[]{new MultiContactFrictionSolver.FrictionContact(POINT_AT_COM,FORWARD,RIGHT,NORMAL,1.0)};
  MultiContactFrictionSolver.solveFriction(contacts,MASS,INERTIA,IDENTITY,IDENTITY,velocity,angular,false,0,0,FRICTION,DT,1);
  double sideSpeedRemaining=velocity.dot(RIGHT);
  assertTrue(Math.abs(sideSpeedRemaining) < 1e-6,
    "a single unconstrained, non-rotating contact's own slip has no rotational coupling to fight - one pass should fully cancel it (mass/wheelCount == mass here with one wheel), got remaining sideSpeed="+sideSpeedRemaining);
 }

 @Test void repeatedPassesConvergeMonotonicallyNotOscillate() {
  Vector3d velocity=new Vector3d(RIGHT).mul(1.0);
  Vector3d angular=new Vector3d(0,0,0);
  var contacts=new MultiContactFrictionSolver.FrictionContact[]{new MultiContactFrictionSolver.FrictionContact(POINT_AT_COM,FORWARD,RIGHT,NORMAL,1.0)};
  double[] sideSpeedAfterEachCall=new double[5];
  for (int i=0;i<5;i++) {
   MultiContactFrictionSolver.solveFriction(contacts,MASS,INERTIA,IDENTITY,IDENTITY,velocity,angular,false,0,0,FRICTION,DT,1);
   sideSpeedAfterEachCall[i]=velocity.dot(RIGHT);
  }
  for (int i=0;i<5;i++) assertTrue(Math.abs(sideSpeedAfterEachCall[i]) < 1e-6,
    "call "+i+": sideSpeed should stay at (approximately) zero, not reintroduce slip - got "+sideSpeedAfterEachCall[i]+" (all: "+java.util.Arrays.toString(sideSpeedAfterEachCall)+")");
 }

 @Test void convergedStateIsIdempotentUnderFurtherSolves() {
  Vector3d velocity=new Vector3d(RIGHT).mul(1.0);
  Vector3d angular=new Vector3d(0,0,0);
  var contacts=new MultiContactFrictionSolver.FrictionContact[]{new MultiContactFrictionSolver.FrictionContact(POINT_AT_COM,FORWARD,RIGHT,NORMAL,1.0)};
  MultiContactFrictionSolver.solveFriction(contacts,MASS,INERTIA,IDENTITY,IDENTITY,velocity,angular,false,0,0,FRICTION,DT,4);
  double afterFirst=velocity.dot(RIGHT);
  MultiContactFrictionSolver.solveFriction(contacts,MASS,INERTIA,IDENTITY,IDENTITY,velocity,angular,false,0,0,FRICTION,DT,4);
  double afterSecond=velocity.dot(RIGHT);
  assertEquals(afterFirst, afterSecond, 1e-9, "re-solving an already-at-rest contact must not reintroduce slip");
 }
}
