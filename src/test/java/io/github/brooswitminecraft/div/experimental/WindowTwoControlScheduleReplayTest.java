package io.github.brooswitminecraft.div.experimental;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Replays window2's exact real raw control schedule, from ticket comment 53123 ("Relative raw
 * control schedule (shaped rise 400N/tick): ... W2 brake44-48, throttle49, drive8000 at50-60;
 * brake/drive0 at61-66; drive8000 at67-97; release98-104; drive8000 at105-121; steer first117"),
 * through the REAL, shipped MultiContactFrictionSolver + the shipped DriveRateLimiter candidate
 * (400N/tick), on the real (non-mirrored) chassis geometry - straight-line only, stopped at t=116
 * (one tick before the real "steer first117"), so steering is never modeled here at all.
 *
 * <p>Per 53123's explicit ask: also compares wheel-ORDER (which array position each rear contact
 * occupies when handed to {@code MultiContactFrictionSolver.solveFriction}, an iterative PGS
 * solver whose per-iteration order can matter) against a reversed rear-pair order, to separate a
 * genuine physical asymmetry effect from a solver order-sensitivity artifact. Diagnostic only -
 * does not touch production code; the real solver/ramp are called exactly as shipped.
 */
class WindowTwoControlScheduleReplayTest {
 private static final double MASS=350.0, GRAVITY=11.0, DT=0.05, FRICTION=1.0, SUSPENSION_TRAVEL=0.3, WHEEL_RADIUS=0.375, LENGTH=WHEEL_RADIUS+SUSPENSION_TRAVEL, BRAKE_FORCE=12000.0, MAX_DRIVE=8000.0, RAMP_RATE=400.0;
 private static final double EQUILIBRIUM_BODY_POSITION_Y=0.803593748062849;
 private static final Vector3d HALF_EXTENTS=new Vector3d(0.42500001192092896,0.4410937510430813,1.050000011920929);
 private static final Vector3d CENTER=new Vector3d(0,0.553593748062849,0.625);
 // Real geometry: rear wheels at indices 0 and 3, x=+0.35/-0.375 - NOT a mirrored pair (comment 53123's "initial rear offsets +.350/-.375").
 private static final Vector3d[] REAL_OFFSETS={
  new Vector3d(0.35,0.125,-0.0625), new Vector3d(-0.375,0.125,1.25),
  new Vector3d(0.375,0.125,1.25), new Vector3d(-0.375,0.125,-0.0625)};
 private static Vector3d anchorLocal(int i) { return new Vector3d(REAL_OFFSETS[i].x, REAL_OFFSETS[i].y+SUSPENSION_TRAVEL*.5, REAL_OFFSETS[i].z).sub(CENTER); }
 private static Vector3d inertiaBody() {
  double a=HALF_EXTENTS.x,b=HALF_EXTENTS.y,c=HALF_EXTENTS.z;
  return new Vector3d(MASS/3*(b*b+c*c), MASS/3*(a*a+c*c), MASS/3*(a*a+b*b));
 }
 private static double suspensionSpring(int n) { return MASS*GRAVITY/(n*SUSPENSION_TRAVEL*.5); }
 private static double suspensionDamper(double spring, int n) { return 2*Math.sqrt(spring*MASS/n)*.7; }
 private static double suspensionForce(double c, double v, double spring, double damper) { return Math.max(0, c*spring-v*damper); }
 private record WheelState(Vector3d anchorWorld, Vector3d up, boolean hit, double normal, Vector3d point, Vector3d forward, Vector3d right) {}
 private record TickSample(int tick, double rollDegrees, double frontNormal, double rearNormal, double shapedDrive) {}

 private static WheelState raycastWheel(int i, double bodyPositionY, Quaterniond orientation, Vector3d velocity, Vector3d angular, double spring, double damper) {
  Vector3d anchorWorld=orientation.transform(new Vector3d(anchorLocal(i)));
  Vector3d up=orientation.transform(new Vector3d(0,1,0));
  Vector3d forward=orientation.transform(new Vector3d(0,0,1));
  Vector3d right=new Vector3d(up).cross(forward);
  double startY=bodyPositionY+anchorWorld.y;
  double tt=startY/(LENGTH*up.y);
  if (!(tt>=0&&tt<=1)||!Double.isFinite(tt)) return new WheelState(anchorWorld,up,false,0,null,forward,right);
  double compression=LENGTH*(1-tt);
  Vector3d point=new Vector3d(anchorWorld).fma(-tt*LENGTH,up);
  Vector3d localVelocity=new Vector3d(angular).cross(anchorWorld).add(velocity);
  double normal=Math.min(MASS*GRAVITY, suspensionForce(compression, localVelocity.dot(up), spring, damper));
  return new WheelState(anchorWorld,up,true,normal,point,forward,right);
 }

 // Exactly comment 53123's "Relative raw control schedule": "brake44-48, throttle49, drive8000
 // at50-60; brake/drive0 at61-66; drive8000 at67-97; release98-104; drive8000 at105-121" - an
 // earlier version of this wrongly dropped the 61-66 brake (misreading "brake/drive0" as "both
 // off" instead of "brake active, drive 0") and started commanded drive at49 instead of50
 // (throttle engaging at49 is not yet raw drive8000 - comment 53125's correction). Asserted
 // below against the exact stated boundary ticks so this cannot silently regress again.
 private static boolean braking(int t) { return (t>=44 && t<=48) || (t>=61 && t<=66); }
 private static double commandedDrive(int t) {
  if ((t>=50&&t<=60) || (t>=67&&t<=97) || (t>=105&&t<=121)) return MAX_DRIVE;
  return 0;
 }
 @Test void scheduleMatchesTheExactRawControlScheduleFromComment53123() {
  assertFalse(braking(43)); assertTrue(braking(44)); assertTrue(braking(48)); assertFalse(braking(49));
  assertFalse(braking(60)); assertTrue(braking(61)); assertTrue(braking(66)); assertFalse(braking(67));
  assertEquals(0, commandedDrive(49), "throttle engaging at49 is not yet raw drive8000");
  assertEquals(MAX_DRIVE, commandedDrive(50)); assertEquals(MAX_DRIVE, commandedDrive(60));
  assertEquals(0, commandedDrive(61)); assertEquals(0, commandedDrive(66));
  assertEquals(MAX_DRIVE, commandedDrive(67)); assertEquals(MAX_DRIVE, commandedDrive(97));
  assertEquals(0, commandedDrive(98)); assertEquals(0, commandedDrive(104));
  assertEquals(MAX_DRIVE, commandedDrive(105)); assertEquals(MAX_DRIVE, commandedDrive(121));
 }

 /** @param order maps solver-array position -> actual wheel index, e.g. {0,1,2,3} normal,
  * {3,1,2,0} with the rear pair's array position swapped. */
 private static java.util.List<TickSample> run(int[] order, int ticks) { return run(order, ticks, MultiContactFrictionSolver.FRICTION_SOLVER_ITERATIONS); }
 private static java.util.List<TickSample> run(int[] order, int ticks, int iterations) {
  Vector3d inertiaBody=inertiaBody();
  Vector3d velocity=new Vector3d(0,0,0), angular=new Vector3d(0,0,0);
  Quaterniond orientation=new Quaterniond();
  double bodyPositionY=EQUILIBRIUM_BODY_POSITION_Y;
  double spring=suspensionSpring(4), damper=suspensionDamper(spring,4);
  var rampState=new DriveRateLimiter.State();
  var samples=new java.util.ArrayList<TickSample>();
  for (int t=0;t<ticks;t++) {
   boolean braking=braking(t);
   double shapedDrive=DriveRateLimiter.shape(rampState, commandedDrive(t), braking, RAMP_RATE);
   Quaterniond inverse=new Quaterniond(orientation).conjugate();
   Vector3d velocity0=new Vector3d(velocity), angular0=new Vector3d(angular);
   WheelState[] wheels=new WheelState[4];
   for (int i=0;i<4;i++) wheels[i]=raycastWheel(i,bodyPositionY,orientation,velocity0,angular0,spring,damper);
   Vector3d solverVelocity=new Vector3d(velocity0), solverAngular=new Vector3d(angular0);
   for (WheelState w : wheels) {
    if (!w.hit()) continue;
    Vector3d vi=new Vector3d(w.up()).mul(w.normal()*DT);
    solverVelocity.add(new Vector3d(vi).div(MASS));
    Vector3d tb=inverse.transform(new Vector3d(w.point()).cross(vi));
    solverAngular.add(orientation.transform(new Vector3d(tb.x/inertiaBody.x,tb.y/inertiaBody.y,tb.z/inertiaBody.z)));
   }
   velocity.add(new Vector3d(0,-GRAVITY*DT,0));
   velocity.add(new Vector3d(solverVelocity).sub(velocity0));
   angular.add(new Vector3d(solverAngular).sub(angular0));
   java.util.List<Integer> activeOrder=new java.util.ArrayList<>();
   for (int idx : order) if (wheels[idx].hit()) activeOrder.add(idx);
   var contacts=new MultiContactFrictionSolver.FrictionContact[activeOrder.size()];
   for (int k=0;k<activeOrder.size();k++) { WheelState w=wheels[activeOrder.get(k)]; contacts[k]=new MultiContactFrictionSolver.FrictionContact(w.point(),w.forward(),w.right(),w.normal(),1.0,(braking?1.0:0.0)); }
   Vector3d bf=new Vector3d(solverVelocity), ba=new Vector3d(solverAngular);
   int activeCount=activeOrder.size();
   double driveForcePerWheel=activeCount==0?0:shapedDrive/4.0; // production divides by ALL wheels (wheels.size()), not just active ones
   var results=MultiContactFrictionSolver.solveFriction(contacts,MASS,inertiaBody,orientation,inverse,solverVelocity,solverAngular,BRAKE_FORCE/4.0,driveForcePerWheel,FRICTION,1.0,DT,iterations);
   velocity.add(new Vector3d(solverVelocity).sub(bf));
   angular.add(new Vector3d(solverAngular).sub(ba));
   double frontNormal=wheels[1].hit()?wheels[1].normal():0;
   frontNormal += wheels[2].hit()?wheels[2].normal():0;
   double rearNormal=(wheels[0].hit()?wheels[0].normal():0) + (wheels[3].hit()?wheels[3].normal():0);
   bodyPositionY += velocity.y*DT;
   orientation.integrate(DT, angular.x, angular.y, angular.z);
   Vector3d rightNow=orientation.transform(new Vector3d(1,0,0));
   double rollDegrees=Math.toDegrees(Math.atan2(rightNow.y, rightNow.x));
   samples.add(new TickSample(t, rollDegrees, frontNormal, rearNormal, shapedDrive));
  }
  return samples;
 }

 // Diagnostic only - reports whole-trajectory numbers for comparison against the real capture;
 // deliberately NOT asserted as a pass/fail physics regression (comment 53125: this harness's
 // approximations mean a passing assertion here would misrepresent it as a verified behavioral
 // match rather than exploratory evidence). See WindowTwoSolverConvergenceTest for the properly
 // isolated (frozen-state, varying-iteration) convergence check this terminal-roll comparison
 // alone cannot substitute for.
 @Test void diagnosticWholeTrajectoryRollBuildupBeforeSteering() {
  var normal = run(new int[]{0,1,2,3}, 117);
  var reversed = run(new int[]{3,1,2,0}, 117);
  System.out.println("[WindowTwoControlScheduleReplayTest] normal order roll at t=44/60/97/104/116: "
    +normal.get(44).rollDegrees()+"/"+normal.get(60).rollDegrees()+"/"+normal.get(97).rollDegrees()+"/"+normal.get(104).rollDegrees()+"/"+normal.get(116).rollDegrees());
  System.out.println("[WindowTwoControlScheduleReplayTest] reversed rear-pair order roll at same ticks: "
    +reversed.get(44).rollDegrees()+"/"+reversed.get(60).rollDegrees()+"/"+reversed.get(97).rollDegrees()+"/"+reversed.get(104).rollDegrees()+"/"+reversed.get(116).rollDegrees());
  System.out.println("[WindowTwoControlScheduleReplayTest] normal order front/rear support at t=97/104/116: "
    +normal.get(97).frontNormal()+"/"+normal.get(97).rearNormal()+"  "+normal.get(104).frontNormal()+"/"+normal.get(104).rearNormal()+"  "+normal.get(116).frontNormal()+"/"+normal.get(116).rearNormal());
  // Real measured: W2 "roll +7.9deg at98 to -37.3deg at109 before steering" - not asserted exactly
  // (this harness's suspension/timing isn't an exact replica of production's own wheel-order/
  // raycast sequencing), but the SIGN and rough MAGNITUDE of a large roll swing in that interval
  // is the thing worth checking against the real capture.
  double rollAt98 = normal.get(98).rollDegrees(), rollAt109 = normal.get(109).rollDegrees();
  System.out.println("[WindowTwoControlScheduleReplayTest] normal order roll at t=98/109 (real measured +7.9deg/-37.3deg): "+rollAt98+"/"+rollAt109);
  double orderSensitivity = Math.abs(normal.get(116).rollDegrees() - reversed.get(116).rollDegrees());
  System.out.println("[WindowTwoControlScheduleReplayTest] wheel-order sensitivity at t=116 (|normal-reversed| roll, degrees): "+orderSensitivity);
 }
}
