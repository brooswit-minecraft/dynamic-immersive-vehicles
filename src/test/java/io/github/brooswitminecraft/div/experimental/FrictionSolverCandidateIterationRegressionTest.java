package io.github.brooswitminecraft.div.experimental;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Candidate-iteration-count regression coverage requested in ticket comment 53130: "finite
 * outputs, friction bounds, no repeated longitudinal accumulation, passive energy/braking/
 * reversal and preserved no-wobble cases at selected higher count." Targets the candidate
 * CANDIDATE_ITERATIONS=64 (justified by {@code FrictionSolverIterationCostBenchmark}'s cost
 * measurement and {@code MultiStateSolverConvergenceTest}'s fixed-point-residual results - cost
 * is trivial at 64 even over a full hour of continuous per-tick calls, and 64 is at or very near
 * a genuine fixed point for every state checked except braking, where it is ~60x closer than 4).
 * Not a replacement for the existing 4-iteration regression suite (MultiContactFrictionSolver*
 * Test) - this specifically re-checks the same invariant CLASSES at the higher candidate count,
 * since none of those existing tests exercise anything but the shipped default.
 */
class FrictionSolverCandidateIterationRegressionTest {
 private static final int CANDIDATE_ITERATIONS=64;
 private static final double MASS=350.0, FRICTION=1.0, DT=0.05, BRAKE_FORCE=12000.0;
 private static final Vector3d INERTIA=new Vector3d(350.0/3*(0.4411*0.4411+1.05*1.05), 350.0/3*(0.425*0.425+1.05*1.05), 350.0/3*(0.425*0.425+0.4411*0.4411));
 /** SYNTHETIC contacts (ticket comment 53131's correction) - x/z match the real ATV's
  * LOCAL_OFFSETS, but y is an arbitrary placeholder, NOT the real COM-relative anchor (which
  * subtracts CENTER and adds half the suspension travel). Fine for these invariant checks
  * (finiteness, friction bounds, accumulation), which don't depend on exact anchor geometry -
  * NOT a claim that these are the real contact points. */
 private static MultiContactFrictionSolver.FrictionContact[] fourWheelContacts(double normalEach) {
  Vector3d[] points={new Vector3d(0.35,-0.15,-0.0625), new Vector3d(-0.375,-0.15,1.25), new Vector3d(0.375,-0.15,1.25), new Vector3d(-0.375,-0.15,-0.0625)};
  var contacts=new MultiContactFrictionSolver.FrictionContact[4];
  for (int i=0;i<4;i++) contacts[i]=new MultiContactFrictionSolver.FrictionContact(points[i], new Vector3d(0,0,1), new Vector3d(1,0,0), normalEach, 1.0);
  return contacts;
 }

 @Test void finiteOutputsAtCandidateIterationsAcrossLoadDriveBrakeCombinations() {
  double[] normals={0, 1e-3, 500, 962.5, 5000};
  double[] drives={-8000, -500, 0, 500, 8000};
  boolean[] brakings={false, true};
  for (double normal : normals) for (double drive : drives) for (boolean braking : brakings) {
   var contacts=fourWheelContacts(normal);
   Vector3d velocity=new Vector3d(0.6,0,1.2), angular=new Vector3d(0.01,0,0.02);
   var results=MultiContactFrictionSolver.solveFriction(contacts,MASS,INERTIA,new Quaterniond(),new Quaterniond(),velocity,angular,
     braking,BRAKE_FORCE/4.0,drive,FRICTION,1.0,DT,CANDIDATE_ITERATIONS);
   for (var r : results) assertTrue(Double.isFinite(r.longitudinal()) && Double.isFinite(r.lateral()),
     "normal="+normal+" drive="+drive+" braking="+braking+" must produce finite output at "+CANDIDATE_ITERATIONS+" iterations");
   assertTrue(Double.isFinite(velocity.x) && Double.isFinite(velocity.y) && Double.isFinite(velocity.z), "velocity must stay finite");
   assertTrue(Double.isFinite(angular.x) && Double.isFinite(angular.y) && Double.isFinite(angular.z), "angular must stay finite");
  }
 }

 @Test void frictionBoundsRespectedAtCandidateIterations() {
  double[] normals={1, 500, 962.5, 5000};
  double[] drives={-8000, 0, 8000};
  for (double normal : normals) for (double drive : drives) {
   var contacts=fourWheelContacts(normal);
   Vector3d velocity=new Vector3d(0.6,0,1.2), angular=new Vector3d(0.01,0,0.02);
   var results=MultiContactFrictionSolver.solveFriction(contacts,MASS,INERTIA,new Quaterniond(),new Quaterniond(),velocity,angular,
     false,BRAKE_FORCE/4.0,drive,FRICTION,1.0,DT,CANDIDATE_ITERATIONS);
   for (var r : results) {
    double mag=Math.hypot(r.longitudinal(), r.lateral());
    assertTrue(mag <= FRICTION*normal + 1e-6, "normal="+normal+" drive="+drive+" must respect the friction-circle bound at "+CANDIDATE_ITERATIONS+" iterations - mag="+mag+" bound="+(FRICTION*normal));
   }
  }
 }

 /** The original accumulation-bug class this investigation found early on: drive must stay
  * PRESCRIBED (replaced each pass), never accumulated ACROSS ITERATIONS. An unsaturated
  * commanded drive realizes slightly below the raw commanded value even at convergence (real,
  * expected inertial cross-coupling between wheels through the shared rigid body - confirmed
  * here by comparing 4 vs 64 iterations directly, not against the raw commanded number, which an
  * earlier draft of this test wrongly assumed should match exactly). The actual accumulation-bug
  * signature would be the realized value growing WITHOUT BOUND as iteration count increases;
  * what must hold is that 4 and 64 iterations land close to each other on an unsaturated contact,
  * not that either equals the naive commanded/wheelCount value. */
 @Test void noRepeatedLongitudinalAccumulationAtCandidateIterations() {
  double driveForcePerWheel=500; // well within the friction budget at normal=5000, mu=1
  var results4=MultiContactFrictionSolver.solveFriction(fourWheelContacts(5000),MASS,INERTIA,new Quaterniond(),new Quaterniond(),new Vector3d(),new Vector3d(),
    false,BRAKE_FORCE/4.0,driveForcePerWheel,FRICTION,1.0,DT,4);
  var results64=MultiContactFrictionSolver.solveFriction(fourWheelContacts(5000),MASS,INERTIA,new Quaterniond(),new Quaterniond(),new Vector3d(),new Vector3d(),
    false,BRAKE_FORCE/4.0,driveForcePerWheel,FRICTION,1.0,DT,CANDIDATE_ITERATIONS);
  for (int i=0;i<4;i++) {
   double diff=Math.abs(results64[i].longitudinal()-results4[i].longitudinal());
   assertTrue(diff <= driveForcePerWheel*0.1,
     "wheel="+i+": an unsaturated contact's realized longitudinal force must not grow substantially with iteration count (4="+results4[i].longitudinal()+" "+CANDIDATE_ITERATIONS+"="+results64[i].longitudinal()+" diff="+diff+")");
   assertTrue(Math.abs(results64[i].longitudinal()) <= driveForcePerWheel + 1e-6,
     "wheel="+i+": realized longitudinal must never EXCEED the commanded value (no accumulation) - got "+results64[i].longitudinal()+" commanded="+driveForcePerWheel);
  }
 }

 /** Braking symmetric both-signs sanity at the candidate count - same spirit as the existing
  * 4-iteration MultiContactFrictionSolverBrakingTest, re-checked here at CANDIDATE_ITERATIONS. */
 @Test void brakingOpposesMotionSymmetricallyInBothDirectionsAtCandidateIterations() {
  for (double sign : new double[]{1.0,-1.0}) {
   var contacts=fourWheelContacts(962.5);
   Vector3d velocity=new Vector3d(0,0,sign*2.0), angular=new Vector3d(0,0,0);
   var results=MultiContactFrictionSolver.solveFriction(contacts,MASS,INERTIA,new Quaterniond(),new Quaterniond(),velocity,angular,
     true,BRAKE_FORCE/4.0,0,FRICTION,1.0,DT,CANDIDATE_ITERATIONS);
   for (var r : results) assertTrue(Math.signum(r.longitudinal())==-Math.signum(sign) || r.longitudinal()==0,
     "braking must oppose motion (sign="+sign+") at "+CANDIDATE_ITERATIONS+" iterations - got longitudinal="+r.longitudinal());
  }
 }

 /** Passive settling sanity at the candidate count - zero drive/brake, quiet symmetric load, the
  * no-wobble case this whole investigation shipped a fix for. Confirms the higher iteration
  * count doesn't itself introduce instability into the already-solved case. */
 @Test void passiveSettlingStaysQuietAtCandidateIterations() {
  var contacts=fourWheelContacts(962.5);
  Vector3d velocity=new Vector3d(0,0,0), angular=new Vector3d(0,0,0);
  var results=MultiContactFrictionSolver.solveFriction(contacts,MASS,INERTIA,new Quaterniond(),new Quaterniond(),velocity,angular,
    false,BRAKE_FORCE/4.0,0,FRICTION,1.0,DT,CANDIDATE_ITERATIONS);
  for (var r : results) assertEquals(0, r.longitudinal(), 1e-9, "zero drive, zero velocity, symmetric load must realize zero longitudinal force at "+CANDIDATE_ITERATIONS+" iterations");
  assertEquals(0, angular.length(), 1e-9, "symmetric quiet load must not introduce any angular velocity at "+CANDIDATE_ITERATIONS+" iterations");
 }
}
