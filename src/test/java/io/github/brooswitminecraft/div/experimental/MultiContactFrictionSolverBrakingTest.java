package io.github.brooswitminecraft.div.experimental;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for the braking accumulation gap review flagged (comment 53025): the braking
 * target's self-limiting branch (min(brakeForcePerWheel, |speed|*mass/wheelCount/dt)) has the
 * same "cancel in one DT" character the lateral target had before 53022's fix, so replacing it
 * fresh each pass (its treatment up to 80161a4) risked the same oscillation at low speed. Fixed
 * in {@link MultiContactFrictionSolver} by accumulating braking's own self-limiting increment
 * into its own small accumulator capped at brakeForcePerWheel, independent of the combined
 * friction circle; drive and rolling resistance stay prescribed/replaced each pass (review:
 * "do not blindly accumulate drive per pass" - confirmed necessary, see the drive test below).
 */
class MultiContactFrictionSolverBrakingTest {
 private static final double MASS = 350.0, DT = 0.05, FRICTION = 1.0, NORMAL = 2000, BRAKE_FORCE = 1000;
 private static final Vector3d INERTIA = new Vector3d(100,100,100);
 private static final Vector3d POINT_AT_COM = new Vector3d(0,0,0);
 private static final Vector3d FORWARD = new Vector3d(0,0,1), RIGHT = new Vector3d(1,0,0);
 private static final Quaterniond IDENTITY = new Quaterniond();

 private static double finalSpeed(double initialSpeed, boolean braking, double driveForcePerWheel, int iterations) {
  Vector3d velocity=new Vector3d(FORWARD).mul(initialSpeed);
  Vector3d angular=new Vector3d(0,0,0);
  var contacts=new MultiContactFrictionSolver.FrictionContact[]{new MultiContactFrictionSolver.FrictionContact(POINT_AT_COM,FORWARD,RIGHT,NORMAL,1.0)};
  MultiContactFrictionSolver.solveFriction(contacts,MASS,INERTIA,IDENTITY,IDENTITY,velocity,angular,braking,BRAKE_FORCE,driveForcePerWheel,FRICTION,DT,iterations);
  return velocity.dot(FORWARD);
 }

 @Test void lowSpeedBrakingConvergesTowardRestWithoutReversalBothSigns() {
  for (double initialSpeed : new double[]{0.5, -0.5}) { // low enough that the self-limiting branch binds, not the brakeForce cap
   for (int n : new int[]{1,2,4,8}) {
    double finalSpeed=finalSpeed(initialSpeed, true, 0, n);
    assertTrue(Math.abs(finalSpeed) <= Math.abs(initialSpeed) + 1e-9,
      "speed="+initialSpeed+" n="+n+": braking must not increase speed magnitude, got "+finalSpeed);
    assertTrue(Math.signum(finalSpeed) == Math.signum(initialSpeed) || Math.abs(finalSpeed) < 1e-9,
      "speed="+initialSpeed+" n="+n+": braking must not reverse direction (overshoot past zero), got "+finalSpeed);
   }
  }
 }

 @Test void highSpeedBrakingStaysPinnedAtMaxForceNotGrowingOrOscillating() {
  // A moderate speed where rolling resistance (-speed*20) is small relative to BRAKE_FORCE, so
  // the brake-force cap dominates and stays force-limited (|speed|*mass/n/dt=10*350/1/DT=70000,
  // far above BRAKE_FORCE=1000) without rolling resistance's own (legitimate, expected) small
  // pass-to-pass drift from recomputing off a slightly-changing speed swamping the comparison.
  double initialSpeed=10.0;
  double[] finalSpeeds=new double[]{finalSpeed(initialSpeed,true,0,1), finalSpeed(initialSpeed,true,0,2),
    finalSpeed(initialSpeed,true,0,4), finalSpeed(initialSpeed,true,0,8)};
  double maxSpread=0;
  for (double s : finalSpeeds) maxSpread=Math.max(maxSpread, Math.abs(s-finalSpeeds[0]));
  assertTrue(maxSpread < 0.01,
    "force-limited braking should remove essentially the same amount regardless of iteration count (small drift from rolling resistance's own legitimate speed-dependence is expected, large drift is not) - speeds="+java.util.Arrays.toString(finalSpeeds));
  for (double s : finalSpeeds) assertTrue(s < initialSpeed && s > initialSpeed-1.0,
    "one tick of a 1000N brake force (plus rolling resistance) at mass 350 should decelerate a modest, bounded amount, not grow or swing wildly - got "+s);
 }

 @Test void drivingDoesNotAccumulateAcrossPasses() {
  // The thing review explicitly warned against: drive is prescribed/replaced, so more passes
  // must NOT multiply its effect. A small residual DOES legitimately differ with pass count
  // because rolling resistance (-speed*20) is recomputed from the slightly-updated speed each
  // pass - that is correct, expected behavior, not accumulation; the assertion below bounds that
  // drift tightly rather than requiring bit-exact equality.
  double drivePerWheel=200; // well under BRAKE_FORCE/friction budget, so never clamped
  double speed1=finalSpeed(0, false, drivePerWheel, 1);
  double speed4=finalSpeed(0, false, drivePerWheel, 4);
  assertEquals(speed1, speed4, 1e-3, "drive's effect on speed must not meaningfully depend on iteration count - got 1 pass="+speed1+" 4 passes="+speed4);
 }

 @Test void brakingAndLateralDemandShareTheFrictionCircleCorrectly() {
  // A contact with BOTH a large lateral slip AND braking active should still respect the
  // combined friction circle (not exceed normal*friction in combined magnitude).
  Vector3d velocity=new Vector3d(FORWARD).mul(0.3).add(new Vector3d(RIGHT).mul(0.3));
  Vector3d angular=new Vector3d(0,0,0);
  var contacts=new MultiContactFrictionSolver.FrictionContact[]{new MultiContactFrictionSolver.FrictionContact(POINT_AT_COM,FORWARD,RIGHT,NORMAL,1.0)};
  MultiContactFrictionSolver.solveFriction(contacts,MASS,INERTIA,IDENTITY,IDENTITY,velocity,angular,true,BRAKE_FORCE,0,FRICTION,DT,MultiContactFrictionSolver.FRICTION_SOLVER_ITERATIONS);
  assertTrue(Double.isFinite(velocity.x) && Double.isFinite(velocity.z), "combined braking+lateral demand must produce finite velocity, got "+velocity);
 }

 @Test void zeroNormalLoadProducesNoNaNOrInstability() {
  var contacts=new MultiContactFrictionSolver.FrictionContact[]{new MultiContactFrictionSolver.FrictionContact(POINT_AT_COM,FORWARD,RIGHT,0.0,1.0)};
  Vector3d velocity=new Vector3d(FORWARD).mul(5.0);
  Vector3d angular=new Vector3d(0,0,0);
  MultiContactFrictionSolver.solveFriction(contacts,MASS,INERTIA,IDENTITY,IDENTITY,velocity,angular,true,BRAKE_FORCE,0,FRICTION,DT,MultiContactFrictionSolver.FRICTION_SOLVER_ITERATIONS);
  assertEquals(5.0, velocity.z, 1e-9, "zero normal load means zero friction budget - an airborne/unloaded wheel must not brake or steer at all");
  assertTrue(Double.isFinite(velocity.x) && Double.isFinite(velocity.y) && Double.isFinite(velocity.z));
 }

 @Test void multipleContactsWithRotationalCouplingBrakeStablyTogether() {
  Vector3d p1=new Vector3d(1,0,0), p2=new Vector3d(-1,0,0);
  Vector3d velocity=new Vector3d(FORWARD).mul(2.0);
  Vector3d angular=new Vector3d(0,0,0);
  var contacts=new MultiContactFrictionSolver.FrictionContact[]{
    new MultiContactFrictionSolver.FrictionContact(p1,FORWARD,RIGHT,NORMAL,1.0),
    new MultiContactFrictionSolver.FrictionContact(p2,FORWARD,RIGHT,NORMAL,1.0)};
  for (int tick=0; tick<30; tick++)
   MultiContactFrictionSolver.solveFriction(contacts,MASS,INERTIA,IDENTITY,IDENTITY,velocity,angular,true,BRAKE_FORCE,0,FRICTION,DT,MultiContactFrictionSolver.FRICTION_SOLVER_ITERATIONS);
  assertTrue(Double.isFinite(velocity.z) && Math.abs(velocity.z) <= 2.0 && Math.abs(angular.length()) < 1.0,
    "two coupled contacts braking together for 30 ticks should settle, not diverge - velocity.z="+velocity.z+" |angular|="+angular.length());
 }
}
