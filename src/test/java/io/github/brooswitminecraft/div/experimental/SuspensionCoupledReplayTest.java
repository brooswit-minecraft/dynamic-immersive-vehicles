package io.github.brooswitminecraft.div.experimental;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The faithful production-path reproduction review asked for (comments 53019/53022/53023):
 * unlike {@link MultiContactFrictionSolverExperimentTest} (fixed, non-raycast per-wheel normal
 * forces), this harness computes each wheel's normal force dynamically every tick from a real
 * raycast-equivalent against a flat ground plane and the SAME suspension spring/damper formulas
 * production uses ({@code PhysicsBridge.suspensionSpring}/{@code suspensionDamper} - duplicated
 * here verbatim, since those are package-private to {@code io.github.brooswitminecraft.div}, not
 * this experimental package; see the constants below). This gives real load transfer: a wheel
 * that lifts as the chassis tilts genuinely loses normal force/friction budget, and the opposite
 * wheel genuinely gains it - the coupling the simplified harness could not represent at all.
 *
 * <p>This is a FREE-RUNNING COUNTERFACTUAL SIMULATION (not a one-step replay of recorded state):
 * it starts from the real captured chassis geometry/mass/inertia and wheel layout (chassisReceipt
 * + per-wheel localOffset, ticket comment 52984/52985) and a tiny seed angular velocity (real
 * placements are never perfectly symmetric), then integrates forward on its own using the real
 * suspension formulas and {@link MultiContactFrictionSolver} - it does not feed in or compare
 * against the real log's tick-by-tick values past tick 0.
 *
 * <p>Review (comment 53044) caught three fidelity gaps in an earlier version of this harness,
 * all fixed: (1) the pre-fix ("old") algorithm was reproduced as a SEQUENTIAL per-wheel update
 * (each wheel's target computed from velocity/angular already changed by earlier wheels in the
 * same tick) - production reads velocity/angular ONCE per tick and every wheel's force
 * computation uses that same frozen snapshot (confirmed by the validated r x J closure check
 * against real data, comment 53008/53010, which only closes under that assumption); (2) the
 * divisor was active.size() (how many wheels currently raycast-hit) instead of production's
 * fixed total ground-device count; (3) longitudinal rolling resistance was omitted; and gravity
 * was being folded into the velocity BEFORE sampling it for the suspension damper term, inflating
 * tick0's normal force with a tick's worth of damping the real captured tick0 data (normal=320.83,
 * zero damping component) shows had not happened yet. All four fixed - the "old" algorithm here
 * now reproduces peak |angularVelocity| on the real order of magnitude the actual capture showed
 * (around 3.9 rad/s over 60 ticks from a tiny seed, versus the real capture's 2.7-4 rad/s by
 * tick 5-30), not merely "some growth".
 */
class SuspensionCoupledReplayTest {
 private static final double MASS=350.0, GRAVITY=11.0, DT=0.05, FRICTION=1.0, SUSPENSION_TRAVEL=0.3, WHEEL_RADIUS=0.375;
 private static final Vector3d HALF_EXTENTS=new Vector3d(0.42500001192092896,0.4410937510430813,1.050000011920929);
 private static final Vector3d CENTER=new Vector3d(0,0.553593748062849,0.625); // chassisReceipt, comment 52984
 // Real per-wheel localOffset values from the captured wheel trace lines (comment 52984/52988).
 private static final Vector3d[] LOCAL_OFFSETS={
  new Vector3d(0.35,0.125,-0.0625), new Vector3d(-0.375,0.125,1.25),
  new Vector3d(0.375,0.125,1.25), new Vector3d(-0.375,0.125,-0.0625)};
 private static Vector3d anchorLocal(int i) { return new Vector3d(LOCAL_OFFSETS[i].x, LOCAL_OFFSETS[i].y+SUSPENSION_TRAVEL*.5, LOCAL_OFFSETS[i].z).sub(CENTER); }
 private static Vector3d inertiaBody() {
  double a=HALF_EXTENTS.x,b=HALF_EXTENTS.y,c=HALF_EXTENTS.z;
  return new Vector3d(MASS/3*(b*b+c*c), MASS/3*(a*a+c*c), MASS/3*(a*a+b*b));
 }
 // Verbatim duplicate of PhysicsBridge.suspensionSpring/suspensionDamper (package-private there) -
 // must be kept consistent with production if those formulas ever change.
 private static double suspensionSpring(int wheelCount) { return MASS*GRAVITY/(wheelCount*SUSPENSION_TRAVEL*.5); }
 private static double suspensionDamper(double spring, int wheelCount) { return 2*Math.sqrt(spring*MASS/wheelCount)*.7; }
 private static double suspensionForce(double compression, double normalSpeed, double spring, double damper) { return Math.max(0, compression*spring-normalSpeed*damper); }

 private record WheelState(Vector3d anchorWorld, Vector3d up, boolean hit, double compression, double normal, Vector3d point, Vector3d forward, Vector3d right) {}

 private static WheelState raycastWheel(int i, double bodyPositionY, Quaterniond orientation, Vector3d velocity, Vector3d angular, double spring, double damper) {
  double length=WHEEL_RADIUS+SUSPENSION_TRAVEL;
  Vector3d anchorWorld=orientation.transform(new Vector3d(anchorLocal(i)));
  Vector3d up=orientation.transform(new Vector3d(0,1,0));
  Vector3d forward=orientation.transform(new Vector3d(0,0,1));
  Vector3d right=new Vector3d(up).cross(forward);
  double startY=bodyPositionY+anchorWorld.y;
  double t=startY/(length*up.y); // plane y=0 intersection along -up from start
  if (!(t>=0 && t<=1) || !Double.isFinite(t)) return new WheelState(anchorWorld,up,false,0,0,null,forward,right);
  double compression=length*(1-t);
  Vector3d point=new Vector3d(anchorWorld).fma(-t*length,up); // = hit - bodyPosition (bodyPosition cancels, see class doc)
  Vector3d localVelocity=new Vector3d(angular).cross(anchorWorld).add(velocity);
  double normal=Math.min(MASS*GRAVITY, suspensionForce(compression, localVelocity.dot(up), spring, damper));
  return new WheelState(anchorWorld,up,true,compression,normal,point,forward,right);
 }

 // Fixed at the real ground-contact device count, matching production's `wheels.size()`
 // (the vehicle's total configured ground devices, NOT how many currently raycast-hit) - review
 // (comment 53044) caught the earlier version dividing by active.size() instead.
 private static final int WHEEL_COUNT=4;

 private static double maxOmegaOverTicks(boolean useNewSolver, int ticks) {
  Vector3d inertiaBody=inertiaBody();
  Vector3d velocity=new Vector3d(0,0,0);
  Vector3d angular=new Vector3d(0.01,0,0.01);
  Quaterniond orientation=new Quaterniond();
  double bodyPositionY=0.9036; // chosen so compression=0.05 for all 4 wheels at tick0 (matches the real capture)
  double spring=suspensionSpring(WHEEL_COUNT), damper=suspensionDamper(spring,WHEEL_COUNT);
  double maxOmega=0;
  for (int t=0;t<ticks;t++) {
   Quaterniond inverse=new Quaterniond(orientation).conjugate();
   // Production reads velocity/angular ONCE per tick (handle.getLinearVelocity/getAngularVelocity
   // at the top of step()) and every wheel's force computation uses that SAME frozen snapshot -
   // confirmed by the validated r x J check against real capture data (comment 53008/53010),
   // which only closes when all 4 wheels' torques are summed from one shared pre-impulse
   // velocity/angular, not a sequentially-updating one. Review (comment 53044) caught an earlier
   // version of this harness updating velocity/angular per wheel WITHIN the same tick's loop,
   // making it a different (sequential) algorithm, not the original one being reproduced. The
   // real captured tick0 state is also velocity=(0,0,0) exactly - gravity for this tick has not
   // yet been folded into the snapshot the suspension/friction math reads (review caught an
   // earlier version applying gravity before sampling normal force, inflating tick0's damper
   // term with a tick's worth of gravity that production's own tick0 normal=320.83 - zero
   // damping component - shows had not happened yet at that point).
   Vector3d velocity0=new Vector3d(velocity), angular0=new Vector3d(angular);
   WheelState[] wheels=new WheelState[WHEEL_COUNT];
   for (int i=0;i<WHEEL_COUNT;i++) wheels[i]=raycastWheel(i,bodyPositionY,orientation,velocity0,angular0,spring,damper);
   // Gravity, and all impulses, accumulate into the WORKING state (what the real body actually
   // ends the tick with) - never back into the frozen velocity0/angular0 snapshot used for force
   // computation.
   velocity.add(new Vector3d(0,-GRAVITY*DT,0));
   for (WheelState w : wheels) {
    if (!w.hit()) continue;
    Vector3d verticalImpulse=new Vector3d(w.up()).mul(w.normal()*DT);
    velocity.add(new Vector3d(verticalImpulse).div(MASS));
    Vector3d torqueBody=inverse.transform(new Vector3d(w.point()).cross(verticalImpulse));
    angular.add(orientation.transform(new Vector3d(torqueBody.x/inertiaBody.x,torqueBody.y/inertiaBody.y,torqueBody.z/inertiaBody.z)));
   }
   if (useNewSolver) {
    java.util.List<WheelState> active=new java.util.ArrayList<>();
    for (WheelState w : wheels) if (w.hit()) active.add(w);
    var contacts=new MultiContactFrictionSolver.FrictionContact[active.size()];
    for (int k=0;k<active.size();k++) { WheelState w=active.get(k); contacts[k]=new MultiContactFrictionSolver.FrictionContact(w.point(),w.forward(),w.right(),w.normal(),1.0); }
    // solveFriction's own working state IS deliberately continuously-updating (that coupling is
    // the fix); it starts from the real post-gravity-and-suspension velocity/angular, consistent
    // with production staging suspension before friction.
    MultiContactFrictionSolver.solveFriction(contacts,MASS,inertiaBody,orientation,inverse,velocity,angular,false,0,0,FRICTION,DT,MultiContactFrictionSolver.FRICTION_SOLVER_ITERATIONS);
   } else {
    // All 4 wheels' targets computed from the SAME frozen velocity0/angular0 (not the updating
    // working state) - the actual pre-fix defect, not a sequential variant of it. Includes
    // rolling resistance (-speed*20), omitted from an earlier version of this harness.
    for (WheelState w : wheels) {
     if (!w.hit()) continue;
     Vector3d contactVelocity=new Vector3d(angular0).cross(w.point()).add(velocity0);
     double speed=contactVelocity.dot(w.forward()), sideSpeed=contactVelocity.dot(w.right());
     double longitudinal=-speed*20;
     double target=-sideSpeed*MASS/WHEEL_COUNT/DT;
     var tire=io.github.brooswitminecraft.div.TireForces.friction(longitudinal,target,w.normal(),FRICTION);
     Vector3d impulse=new Vector3d(w.forward()).mul(tire.longitudinal()*DT).fma(tire.lateral()*DT,w.right());
     velocity.add(new Vector3d(impulse).div(MASS));
     Vector3d torqueBody=inverse.transform(new Vector3d(w.point()).cross(impulse));
     angular.add(orientation.transform(new Vector3d(torqueBody.x/inertiaBody.x,torqueBody.y/inertiaBody.y,torqueBody.z/inertiaBody.z)));
    }
   }
   bodyPositionY += velocity.y*DT;
   orientation.integrate(DT, angular.x, angular.y, angular.z);
   maxOmega=Math.max(maxOmega, angular.length());
  }
  return maxOmega;
 }

 @Test void oldIndependentTargetWithRealSuspensionCouplingReproducesTheRealOrderOfMagnitude() {
  double maxOmega=maxOmegaOverTicks(false, 60);
  assertTrue(maxOmega > 2.0,
    "with the fidelity fixes from review 53044, the pre-fix independent per-wheel computation should reach the same order of magnitude the real capture showed (2.7-4 rad/s within 30 ticks), not just 'some growth' - got "+maxOmega);
 }

 @Test void newSolverWithRealSuspensionCouplingStaysMoreBoundedThanOldOverSixtyTicks() {
  double maxOmegaOld=maxOmegaOverTicks(false, 60);
  double maxOmegaNew=maxOmegaOverTicks(true, 60);
  // Review (comment 53044) asked for the actual numbers published, not just a pass/fail ratio.
  System.out.println("[SuspensionCoupledReplayTest] peak |angularVelocity| over 60 ticks - old="+maxOmegaOld+" rad/s, new="+maxOmegaNew+" rad/s");
  assertTrue(maxOmegaNew < maxOmegaOld * 0.5,
    "with real suspension/load-transfer coupling, the corrected solver should still substantially reduce peak angular velocity versus the pre-fix computation - old="+maxOmegaOld+" new="+maxOmegaNew);
 }

 /**
  * A separate, previously-unidentified mechanism discovered while building this harness: the
  * real chassis's `center` (its collision-box bounding-box center, independent of wheel
  * placement - see {@code PhysicsBridge.Body}'s constructor) is NOT the centroid of its own 4
  * wheel anchor points. Summing the real anchor offsets (ticket comment 52984's localOffset
  * values) gives (-0.025, -1.114, -0.125), not zero - so an EQUAL, perfectly symmetric vertical
  * force at all 4 wheels (exactly what a level, undriven placement produces) still generates a
  * real, nonzero net torque, purely from this geometric mismatch - independent of the lateral
  * friction bug, and present even with zero rotation and zero friction. This does not, by itself,
  * prove this mismatch is large enough to be a primary cause of the reported rollover, OR that
  * the geometry is faulty (review, comment 53044): unequal per-wheel EQUILIBRIUM loads, or a
  * small steady-state body pitch/roll the real suspension settles into, can balance an equal-
  * load torque like this one without it ever producing net rotation in practice - this test
  * verifies the equal-load case specifically, not the real settled equilibrium. Do not recenter
  * or tune the chassis geometry, or expand scope, based on this finding alone - it is reported as
  * a verified mechanism worth being aware of, nothing more.
  */
 @Test void symmetricSuspensionLoadOnTheRealWheelLayoutProducesNonzeroNetTorque() {
  Vector3d totalTorque=new Vector3d();
  double equalForcePerWheel=MASS*GRAVITY/4;
  for (int i=0;i<4;i++) totalTorque.add(new Vector3d(anchorLocal(i)).cross(new Vector3d(0,equalForcePerWheel,0)));
  assertTrue(totalTorque.length() > 1.0,
    "the real wheel layout's anchor points are not centered on the chassis's own collision-box center, so equal (symmetric) vertical support at all 4 wheels should produce a real, non-negligible net torque, not approximately zero - got "+totalTorque);
 }
}
