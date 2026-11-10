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

 private static double maxOmegaOverTicks(boolean useNewSolver, int ticks) {
  Vector3d inertiaBody=inertiaBody();
  Vector3d velocity=new Vector3d(0,0,0);
  Vector3d angular=new Vector3d(0.01,0,0.01);
  Quaterniond orientation=new Quaterniond();
  double bodyPositionY=0.9036; // chosen so compression=0.05 for all 4 wheels at tick0 (matches the real capture - see class doc derivation in commit history)
  int wheelCount=4;
  double spring=suspensionSpring(wheelCount), damper=suspensionDamper(spring,wheelCount);
  double maxOmega=0;
  for (int t=0;t<ticks;t++) {
   Quaterniond inverse=new Quaterniond(orientation).conjugate();
   velocity.add(new Vector3d(0,-GRAVITY*DT,0));
   WheelState[] wheels=new WheelState[wheelCount];
   for (int i=0;i<wheelCount;i++) wheels[i]=raycastWheel(i,bodyPositionY,orientation,velocity,angular,spring,damper);
   java.util.List<Integer> active=new java.util.ArrayList<>();
   for (int i=0;i<wheelCount;i++) if (wheels[i].hit()) active.add(i);
   // Stage vertical/suspension impulses first (same order production uses).
   for (int i : active) {
    WheelState w=wheels[i];
    Vector3d verticalImpulse=new Vector3d(w.up()).mul(w.normal()*DT);
    velocity.add(new Vector3d(verticalImpulse).div(MASS));
    Vector3d torqueBody=inverse.transform(new Vector3d(w.point()).cross(verticalImpulse));
    angular.add(orientation.transform(new Vector3d(torqueBody.x/inertiaBody.x,torqueBody.y/inertiaBody.y,torqueBody.z/inertiaBody.z)));
   }
   if (useNewSolver) {
    var contacts=new MultiContactFrictionSolver.FrictionContact[active.size()];
    for (int k=0;k<active.size();k++) { WheelState w=wheels[active.get(k)]; contacts[k]=new MultiContactFrictionSolver.FrictionContact(w.point(),w.forward(),w.right(),w.normal(),1.0); }
    MultiContactFrictionSolver.solveFriction(contacts,MASS,inertiaBody,orientation,inverse,velocity,angular,false,0,0,FRICTION,DT,MultiContactFrictionSolver.FRICTION_SOLVER_ITERATIONS);
   } else {
    for (int i : active) {
     WheelState w=wheels[i];
     Vector3d contactVelocity=new Vector3d(angular).cross(w.point()).add(velocity);
     double sideSpeed=contactVelocity.dot(w.right());
     double target=-sideSpeed*MASS/active.size()/DT;
     var tire=io.github.brooswitminecraft.div.TireForces.friction(0,target,w.normal(),FRICTION);
     Vector3d impulse=new Vector3d(w.right()).mul(tire.lateral()*DT);
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

 @Test void oldIndependentTargetWithRealSuspensionCouplingStillGrowsSubstantially() {
  double maxOmega=maxOmegaOverTicks(false, 60);
  assertTrue(maxOmega > 0.05,
    "with real dynamic suspension coupling (not the simplified fixed-normal harness), the pre-fix independent per-wheel computation should still show substantial growth from a tiny seed - got "+maxOmega);
 }

 @Test void newSolverWithRealSuspensionCouplingStaysMoreBoundedThanOldOverSixtyTicks() {
  double maxOmegaOld=maxOmegaOverTicks(false, 60);
  double maxOmegaNew=maxOmegaOverTicks(true, 60);
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
  * prove this mismatch is large enough to be a primary cause of the reported rollover (that needs
  * comparing its magnitude against the friction-driven torque across a full trajectory, not done
  * here) - it is reported as a newly-found, mathematically verified contributing mechanism, not a
  * confirmed root cause.
  */
 @Test void symmetricSuspensionLoadOnTheRealWheelLayoutProducesNonzeroNetTorque() {
  Vector3d totalTorque=new Vector3d();
  double equalForcePerWheel=MASS*GRAVITY/4;
  for (int i=0;i<4;i++) totalTorque.add(new Vector3d(anchorLocal(i)).cross(new Vector3d(0,equalForcePerWheel,0)));
  assertTrue(totalTorque.length() > 1.0,
    "the real wheel layout's anchor points are not centered on the chassis's own collision-box center, so equal (symmetric) vertical support at all 4 wheels should produce a real, non-negligible net torque, not approximately zero - got "+totalTorque);
 }
}
