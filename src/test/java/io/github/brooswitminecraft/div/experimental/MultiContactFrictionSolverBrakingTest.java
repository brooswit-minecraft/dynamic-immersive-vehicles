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
  MultiContactFrictionSolver.solveFriction(contacts,MASS,INERTIA,IDENTITY,IDENTITY,velocity,angular,braking,BRAKE_FORCE,driveForcePerWheel,FRICTION,1.0,DT,iterations);
  return velocity.dot(FORWARD);
 }

 /**
  * Review (comment 53034) caught that my first version of this test used ±0.5, which at
  * mass=350, dt=0.05, brakeForce=1000 is still FORCE-CAPPED (the self-limiting branch only
  * binds below brakeForcePerWheel*dt/mass = 0.142857 m/s) - it never exercised the regime this
  * test is named for. Using ±0.01 and ±0.1 instead, both genuinely below that threshold.
  *
  * <p>Review also caught that a real, tiny, EXPLAINABLE sign reversal exists even with the fix:
  * at a COM contact, one pass's brake correction exactly cancels the measured speed via the
  * effective-mass formula, but rolling resistance (-speed*20, a separate, independent damping
  * term, not part of the braking accumulator) ALSO removes a small amount of speed in the SAME
  * pass - together they remove very slightly more than the original speed, overshooting past
  * zero by an amount proportional to rolling resistance's own (much smaller) contribution. This
  * is asserted explicitly and bounded below, not hidden behind a loose "near zero" tolerance.
  */
 @Test void lowSpeedBrakingConvergesTowardRestWithATinyExplainableOvershootBothSigns() {
  for (double initialSpeed : new double[]{0.01, -0.01, 0.1, -0.1}) { // genuinely below the 0.142857 self-limiting threshold
   for (int n : new int[]{1,2,4,8,32,64}) { // 32/64 added: ticket comment 53131, candidate-iteration coverage
    double finalSpeed=finalSpeed(initialSpeed, true, 0, n);
    // Bound: the overshoot comes from rolling resistance's contribution to the SAME pass that
    // cancels the bulk of the speed via the brake accumulator - its magnitude is at most on the
    // order of initialSpeed*20*dt/mass (one pass's rolling-resistance deceleration), not
    // unbounded or growing with iteration count.
    double maxExplainableOvershoot=Math.abs(initialSpeed)*20*DT/MASS*2; // x2 margin
    assertTrue(Math.abs(finalSpeed) <= maxExplainableOvershoot,
      "speed="+initialSpeed+" n="+n+": braking should reach (approximately) rest, with any overshoot bounded by rolling resistance's own small contribution (bound "+maxExplainableOvershoot+") - got "+finalSpeed);
   }
  }
 }

 @Test void lowSpeedBrakingAtARealisticOffCenterContactCancelsContactSlipNotNecessarilyComVelocity() {
  // Review (comment 53034): test realistic contact height/inertia and low-speed coupled
  // response, not only the COM case - a real wheel contact is well below the COM, giving genuine
  // rotational coupling the COM case (zero moment arm) cannot exercise at all.
  //
  // Review ALSO corrected my first reading of this result (comment 53040): the solver targets
  // and cancels the CONTACT-POINT slip - (angular x point + velocity).dot(forward) - not the
  // COM velocity directly. An isolated off-center contact legitimately leaves most of the COM
  // velocity in place (~68% of it, here), compensated by induced rotation, while the actual
  // slip AT THAT CONTACT POINT is cancelled almost completely (measured: COM residual ~6.8e-3
  // for a 1e-2 initial speed, but contact-point slip residual ~8e-5 - two orders of magnitude
  // smaller). That is the solver doing exactly what it is designed to do, not an under-
  // cancellation - so this test asserts the CONTACT slip converges toward zero, and separately
  // reports (without asserting smallness of) the COM velocity residual, rather than conflating
  // the two quantities the way an earlier version of this test did.
  Vector3d offCenterPoint=new Vector3d(0.35,-0.9,0.625); // representative of the real ATV geometry (ticket chassisReceipt)
  Vector3d inertia=new Vector3d(150,150,45); // representative diagonal solid-box tensor, same order as the real ATV's
  for (double initialSpeed : new double[]{0.01,-0.01,0.1,-0.1}) {
   for (int n : new int[]{1,2,4,8,32,64}) { // 32/64 added: ticket comment 53131, candidate-iteration coverage
    Vector3d velocity=new Vector3d(FORWARD).mul(initialSpeed);
    Vector3d angular=new Vector3d(0,0,0);
    var contacts=new MultiContactFrictionSolver.FrictionContact[]{new MultiContactFrictionSolver.FrictionContact(offCenterPoint,FORWARD,RIGHT,NORMAL,1.0)};
    MultiContactFrictionSolver.solveFriction(contacts,MASS,inertia,IDENTITY,IDENTITY,velocity,angular,true,BRAKE_FORCE,0,FRICTION,1.0,DT,n);
    assertTrue(Double.isFinite(angular.length()), "off-center braking must not produce a non-finite induced angular velocity");
    double contactSlip=new Vector3d(angular).cross(offCenterPoint).add(velocity).dot(FORWARD);
    assertTrue(Math.abs(contactSlip) < Math.abs(initialSpeed)*0.05,
      "speed="+initialSpeed+" n="+n+": the ACTUAL contact-point slip (not COM velocity) should be nearly cancelled - got contactSlip="+contactSlip+" from initial "+initialSpeed+" (COM velocity residual="+velocity.dot(FORWARD)+", expected to remain substantial - compensated by induced rotation, not a failure)");
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
    finalSpeed(initialSpeed,true,0,4), finalSpeed(initialSpeed,true,0,8),
    finalSpeed(initialSpeed,true,0,32), finalSpeed(initialSpeed,true,0,64)}; // 32/64: ticket comment 53131
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
  double speed64=finalSpeed(0, false, drivePerWheel, 64); // ticket comment 53131: candidate-iteration coverage
  assertEquals(speed1, speed64, 1e-3, "drive's effect on speed must not meaningfully depend on iteration count, including the candidate 64 - got 1 pass="+speed1+" 64 passes="+speed64);
 }

 @Test void brakingAndLateralDemandShareTheFrictionCircleCorrectly() {
  // Review (comment 53034): this test's name/comment claimed friction-circle compliance but
  // only asserted finiteness. Assert the actual returned force magnitude directly against
  // mu*normal, using a demand large enough to actually saturate the circle (small inputs like
  // the previous version's 0.3/0.3 m/s never got near the budget, so compliance was untested).
  Vector3d velocity=new Vector3d(FORWARD).mul(50).add(new Vector3d(RIGHT).mul(50)); // large demand, well over budget
  Vector3d angular=new Vector3d(0,0,0);
  var contacts=new MultiContactFrictionSolver.FrictionContact[]{new MultiContactFrictionSolver.FrictionContact(POINT_AT_COM,FORWARD,RIGHT,NORMAL,1.0)};
  var results=MultiContactFrictionSolver.solveFriction(contacts,MASS,INERTIA,IDENTITY,IDENTITY,velocity,angular,true,BRAKE_FORCE,0,FRICTION,1.0,DT,MultiContactFrictionSolver.FRICTION_SOLVER_ITERATIONS);
  double magnitude=Math.hypot(results[0].longitudinal(),results[0].lateral());
  double budget=NORMAL*FRICTION;
  assertTrue(magnitude <= budget+1e-6,
    "combined braking+lateral force magnitude ("+magnitude+") must not exceed the friction budget mu*normal ("+budget+")");
  assertTrue(Double.isFinite(velocity.x) && Double.isFinite(velocity.z), "combined braking+lateral demand must produce finite velocity, got "+velocity);
 }

 @Test void zeroNormalLoadProducesNoNaNOrInstability() {
  var contacts=new MultiContactFrictionSolver.FrictionContact[]{new MultiContactFrictionSolver.FrictionContact(POINT_AT_COM,FORWARD,RIGHT,0.0,1.0)};
  Vector3d velocity=new Vector3d(FORWARD).mul(5.0);
  Vector3d angular=new Vector3d(0,0,0);
  MultiContactFrictionSolver.solveFriction(contacts,MASS,INERTIA,IDENTITY,IDENTITY,velocity,angular,true,BRAKE_FORCE,0,FRICTION,1.0,DT,MultiContactFrictionSolver.FRICTION_SOLVER_ITERATIONS);
  assertEquals(5.0, velocity.z, 1e-9, "zero normal load means zero friction budget - an airborne/unloaded wheel must not brake or steer at all");
  assertTrue(Double.isFinite(velocity.x) && Double.isFinite(velocity.y) && Double.isFinite(velocity.z));
 }

 @Test void multipleContactsWithRotationalCouplingBrakeStablyTogether() {
  multipleContactsWithRotationalCouplingBrakeStablyTogetherAt(MultiContactFrictionSolver.FRICTION_SOLVER_ITERATIONS);
 }
 /** Ticket comment 53131: re-check this exact multi-tick coupled-braking settling case at the
  * candidate iteration count too, not only the shipped default. */
 @Test void multipleContactsWithRotationalCouplingBrakeStablyTogetherAtCandidateIterations() {
  multipleContactsWithRotationalCouplingBrakeStablyTogetherAt(64);
 }
 private static void multipleContactsWithRotationalCouplingBrakeStablyTogetherAt(int iterations) {
  Vector3d p1=new Vector3d(1,0,0), p2=new Vector3d(-1,0,0);
  Vector3d velocity=new Vector3d(FORWARD).mul(2.0);
  Vector3d angular=new Vector3d(0,0,0);
  var contacts=new MultiContactFrictionSolver.FrictionContact[]{
    new MultiContactFrictionSolver.FrictionContact(p1,FORWARD,RIGHT,NORMAL,1.0),
    new MultiContactFrictionSolver.FrictionContact(p2,FORWARD,RIGHT,NORMAL,1.0)};
  for (int tick=0; tick<30; tick++)
   MultiContactFrictionSolver.solveFriction(contacts,MASS,INERTIA,IDENTITY,IDENTITY,velocity,angular,true,BRAKE_FORCE,0,FRICTION,1.0,DT,iterations);
  assertTrue(Double.isFinite(velocity.z) && Math.abs(velocity.z) <= 2.0 && Math.abs(angular.length()) < 1.0,
    "two coupled contacts braking together for 30 ticks at "+iterations+" iterations should settle, not diverge - velocity.z="+velocity.z+" |angular|="+angular.length());
 }
}
