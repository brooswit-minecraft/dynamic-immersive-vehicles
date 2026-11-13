package io.github.brooswitminecraft.div.experimental;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Second of two pieces requested in ticket comment 53127, corrected per comment 53130's review of
 * the first attempt (a9ecf23): that attempt compared {@code abs(mag64-mag4)} (a magnitude-only
 * diff, hiding direction changes, and labeled a "residual" when it was really just two different
 * approximations compared) and treated a saturated contact's own cross-iteration difference as
 * automatically "expected" rather than a real difference. Fixed here:
 * <ul>
 *  <li>{@code crossIterationDiff}/{@code vectorDiff} compare the full (longitudinal,lateral)
 *  force VECTOR between two iteration counts - not just its magnitude.</li>
 *  <li>a genuine {@code fixedPointResidual} is ALSO computed - one further sweep applied to the
 *  SAME candidate (N vs N+1 iterations on the identical frozen input), the actual "is this near
 *  a fixed point" question, kept explicitly distinct from a cross-iteration diff (4 vs 64, or
 *  32 vs 64) which only says two different approximations differ, not that either has converged.
 *  </li>
 *  <li>saturation is reported but never used to wave away a 4-vs-64 difference as "expected" -
 *  only persistent slip AT a stable bound is ordinary friction-circle behavior.</li>
 * </ul>
 *
 * <p>Five frozen states, each reached by replaying the real corrected window2 schedule (see
 * {@code WindowTwoControlScheduleReplayTest}) normally up to that tick, in NORMAL wheel order and
 * the shipped 4-iteration solver (i.e. "how the shipped build would actually have gotten here"):
 * t=0 (zero CONTROLS, not zero/low normal load - comment 53130's correction; a separate explicit
 * zero/low-normal-load synthetic contact check is below), t=46 (mid-braking), t=55/t=70 (mid
 * drive), t=109 (this harness's own peak-divergence tick, not the real captured production
 * state).
 */
class MultiStateSolverConvergenceTest {
 private static final double MASS=350.0, GRAVITY=11.0, DT=0.05, FRICTION=1.0, SUSPENSION_TRAVEL=0.3, WHEEL_RADIUS=0.375, LENGTH=WHEEL_RADIUS+SUSPENSION_TRAVEL, BRAKE_FORCE=12000.0, MAX_DRIVE=8000.0, RAMP_RATE=400.0;
 private static final double EQUILIBRIUM_BODY_POSITION_Y=0.803593748062849;
 private static final Vector3d HALF_EXTENTS=new Vector3d(0.42500001192092896,0.4410937510430813,1.050000011920929);
 private static final Vector3d CENTER=new Vector3d(0,0.553593748062849,0.625);
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
 private record WheelState(Vector3d up, boolean hit, double normal, Vector3d point, Vector3d forward, Vector3d right) {}
 private static WheelState raycastWheel(int i, double bodyPositionY, Quaterniond orientation, Vector3d velocity, Vector3d angular, double spring, double damper) {
  Vector3d anchorWorld=orientation.transform(new Vector3d(anchorLocal(i)));
  Vector3d up=orientation.transform(new Vector3d(0,1,0));
  Vector3d forward=orientation.transform(new Vector3d(0,0,1));
  Vector3d right=new Vector3d(up).cross(forward);
  double startY=bodyPositionY+anchorWorld.y;
  double tt=startY/(LENGTH*up.y);
  if (!(tt>=0&&tt<=1)||!Double.isFinite(tt)) return new WheelState(up,false,0,null,forward,right);
  double compression=LENGTH*(1-tt);
  Vector3d point=new Vector3d(anchorWorld).fma(-tt*LENGTH,up);
  Vector3d localVelocity=new Vector3d(angular).cross(anchorWorld).add(velocity);
  double normal=Math.min(MASS*GRAVITY, suspensionForce(compression, localVelocity.dot(up), spring, damper));
  return new WheelState(up,true,normal,point,forward,right);
 }
 private static boolean braking(int t) { return (t>=44 && t<=48) || (t>=61 && t<=66); }
 private static double commandedDrive(int t) {
  if ((t>=50&&t<=60) || (t>=67&&t<=97) || (t>=105&&t<=121)) return MAX_DRIVE;
  return 0;
 }
 private record FrozenState(WheelState[] wheels, Vector3d solverVelocity, Vector3d solverAngular, Quaterniond orientation, Quaterniond inverse, boolean braking, double driveForcePerWheel) {}

 private static FrozenState freeze(int freezeAtTick) {
  Vector3d inertiaBody=inertiaBody();
  Vector3d velocity=new Vector3d(0,0,0), angular=new Vector3d(0,0,0);
  Quaterniond orientation=new Quaterniond();
  double bodyPositionY=EQUILIBRIUM_BODY_POSITION_Y;
  double spring=suspensionSpring(4), damper=suspensionDamper(spring,4);
  var rampState=new DriveRateLimiter.State();
  for (int t=0;t<freezeAtTick;t++) {
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
   java.util.List<WheelState> active=new java.util.ArrayList<>();
   for (WheelState w : wheels) if (w.hit()) active.add(w);
   var contacts=new MultiContactFrictionSolver.FrictionContact[active.size()];
   for (int k=0;k<active.size();k++) { WheelState w=active.get(k); contacts[k]=new MultiContactFrictionSolver.FrictionContact(w.point(),w.forward(),w.right(),w.normal(),1.0); }
   Vector3d bf=new Vector3d(solverVelocity), ba=new Vector3d(solverAngular);
   double driveForcePerWheel=active.isEmpty()?0:shapedDrive/4.0;
   MultiContactFrictionSolver.solveFriction(contacts,MASS,inertiaBody,orientation,inverse,solverVelocity,solverAngular,
     (braking?1.0:0.0),BRAKE_FORCE/4.0,driveForcePerWheel,FRICTION,1.0,DT,MultiContactFrictionSolver.FRICTION_SOLVER_ITERATIONS);
   velocity.add(new Vector3d(solverVelocity).sub(bf));
   angular.add(new Vector3d(solverAngular).sub(ba));
   bodyPositionY += velocity.y*DT;
   orientation.integrate(DT, angular.x, angular.y, angular.z);
  }
  boolean braking=braking(freezeAtTick);
  double shapedDrive=DriveRateLimiter.shape(rampState, commandedDrive(freezeAtTick), braking, RAMP_RATE);
  Quaterniond inverse=new Quaterniond(orientation).conjugate();
  WheelState[] wheels=new WheelState[4];
  for (int i=0;i<4;i++) wheels[i]=raycastWheel(i,bodyPositionY,orientation,velocity,angular,spring,damper);
  Vector3d solverVelocity=new Vector3d(velocity), solverAngular=new Vector3d(angular);
  for (WheelState w : wheels) {
   if (!w.hit()) continue;
   Vector3d vi=new Vector3d(w.up()).mul(w.normal()*DT);
   solverVelocity.add(new Vector3d(vi).div(MASS));
   Vector3d tb=inverse.transform(new Vector3d(w.point()).cross(vi));
   solverAngular.add(orientation.transform(new Vector3d(tb.x/inertiaBody.x,tb.y/inertiaBody.y,tb.z/inertiaBody.z)));
  }
  int activeCount=0; for (WheelState w:wheels) if (w.hit()) activeCount++;
  double driveForcePerWheel=activeCount==0?0:shapedDrive/4.0;
  return new FrozenState(wheels, solverVelocity, solverAngular, orientation, inverse, braking, driveForcePerWheel);
 }

 private record SolveOutcome(Vector3d velocityAfter, Vector3d angularAfter, MultiContactFrictionSolver.FrictionResult[] results, int[] activeWheelIndex) {}
 private static SolveOutcome solveFrozen(FrozenState frozen, int iterations) {
  java.util.List<Integer> active=new java.util.ArrayList<>();
  for (int i=0;i<4;i++) if (frozen.wheels()[i].hit()) active.add(i);
  var contacts=new MultiContactFrictionSolver.FrictionContact[active.size()];
  for (int k=0;k<active.size();k++) { WheelState w=frozen.wheels()[active.get(k)]; contacts[k]=new MultiContactFrictionSolver.FrictionContact(w.point(),w.forward(),w.right(),w.normal(),1.0); }
  Vector3d velocity=new Vector3d(frozen.solverVelocity()), angular=new Vector3d(frozen.solverAngular());
  var results=MultiContactFrictionSolver.solveFriction(contacts,MASS,inertiaBody(),frozen.orientation(),frozen.inverse(),velocity,angular,
    (frozen.braking()?1.0:0.0),BRAKE_FORCE/4.0,frozen.driveForcePerWheel(),FRICTION,1.0,DT,iterations);
  int[] activeWheelIndex=active.stream().mapToInt(Integer::intValue).toArray();
  return new SolveOutcome(velocity, angular, results, activeWheelIndex);
 }

 /** Vector (not magnitude) difference between two FrictionResults in their own
  * longitudinal/lateral basis - comment 53130's correction: a magnitude-only diff
  * ({@code abs(mag64-mag4)}) hides a direction change even when it reports near-zero. */
 private static double vectorDiff(MultiContactFrictionSolver.FrictionResult a, MultiContactFrictionSolver.FrictionResult b) {
  return Math.hypot(b.longitudinal()-a.longitudinal(), b.lateral()-a.lateral());
 }

 // Diagnostic only, deliberately not asserting a numeric tolerance (comment 53130: "No need to
 // assert that 4 already converges - we know it does not"). Reports TWO distinct quantities,
 // kept explicitly separate per 53130's correction:
 //  - crossIterationDiff(4,64): how much the 4- and 64-iteration outputs differ from EACH OTHER -
 //    not a solver residual, just two different approximations compared.
 //  - fixedPointResidual(N): how much ONE further sweep changes the N-iteration candidate itself
 //    (solve at N vs N+1 on the identical frozen input) - the actual "is this near a fixed point"
 //    question, independent of what a different N entirely produces.
 // Saturation is reported per-contact but, per 53130, does NOT mean a 4-vs-64 difference there is
 // "expected/converged" - only that persistent slip at the bound is not itself evidence of
 // non-convergence; a MAGNITUDE or direction change at/near the bound is still a real difference.
 @Test void frozenStatesCrossIterationDiffAndFixedPointResidual() {
  int[] states={0,46,55,70,109};
  for (int tick : states) {
   FrozenState frozen=freeze(tick);
   var at4=solveFrozen(frozen,4);
   var at64=solveFrozen(frozen,64);
   var at65=solveFrozen(frozen,65);
   var at32=solveFrozen(frozen,32);
   var at33=solveFrozen(frozen,33);
   double velocityCrossDiff=new Vector3d(at64.velocityAfter()).sub(at4.velocityAfter()).length();
   double angularCrossDiff=new Vector3d(at64.angularAfter()).sub(at4.angularAfter()).length();
   double velocityFixedPointResidual64=new Vector3d(at65.velocityAfter()).sub(at64.velocityAfter()).length();
   double angularFixedPointResidual64=new Vector3d(at65.angularAfter()).sub(at64.angularAfter()).length();
   double velocityFixedPointResidual32=new Vector3d(at33.velocityAfter()).sub(at32.velocityAfter()).length();
   double angularFixedPointResidual32=new Vector3d(at33.angularAfter()).sub(at32.angularAfter()).length();
   double velocity32to64=new Vector3d(at64.velocityAfter()).sub(at32.velocityAfter()).length();
   double angular32to64=new Vector3d(at64.angularAfter()).sub(at32.angularAfter()).length();
   System.out.println("[MultiStateSolverConvergenceTest] t="+tick+" activeWheels="+at4.activeWheelIndex().length
     +" braking="+frozen.braking()+" driveForcePerWheel="+frozen.driveForcePerWheel()
     +" crossIterationDiff(4,64): |dv|="+velocityCrossDiff+" |domega|="+angularCrossDiff
     +" fixedPointResidual(64->65): |dv|="+velocityFixedPointResidual64+" |domega|="+angularFixedPointResidual64
     +" fixedPointResidual(32->33): |dv|="+velocityFixedPointResidual32+" |domega|="+angularFixedPointResidual32
     +" diff(32,64): |dv|="+velocity32to64+" |domega|="+angular32to64);
   for (int k=0;k<at64.activeWheelIndex().length;k++) {
    int wheelIdx=at64.activeWheelIndex()[k];
    double normal=frozen.wheels()[wheelIdx].normal();
    double bound=FRICTION*normal;
    var r4=at4.results()[k]; var r64=at64.results()[k]; var r65=at65.results()[k];
    double mag64=Math.hypot(r64.longitudinal(), r64.lateral());
    boolean saturated = bound>0 && mag64 >= bound*0.98; // at/near the friction-circle bound - does NOT by itself excuse a cross-iteration difference
    System.out.println("[MultiStateSolverConvergenceTest]   wheel="+wheelIdx+" normal="+normal+" bound="+bound+" mag64="+mag64+" saturated="+saturated
      +" crossIterationVectorDiff(4,64)="+vectorDiff(r4,r64)+" fixedPointVectorResidual(64->65)="+vectorDiff(r64,r65));
   }
  }
 }

 /** Comment 53130: "t0 is zero CONTROLS, not zero/low normal load; add explicitly zero/low-load
  * contacts." A wheel whose suspension is barely compressed (near its full-extension limit) has
  * a near-zero normal force and therefore a near-zero friction bound - checks the solver still
  * returns finite output and respects that (near-zero) bound rather than dividing by ~0. */
 @Test void explicitZeroAndLowNormalLoadContactsStayFiniteAndWithinBound() {
  Vector3d inertiaBody=inertiaBody();
  Quaterniond orientation=new Quaterniond(), inverse=new Quaterniond();
  double[] normals={0.0, 1e-6, 1.0, 50.0};
  for (double normal : normals) {
   var contacts=new MultiContactFrictionSolver.FrictionContact[]{
     new MultiContactFrictionSolver.FrictionContact(new Vector3d(0.375,-0.15,1.25), new Vector3d(0,0,1), new Vector3d(1,0,0), normal, 1.0)};
   Vector3d velocity=new Vector3d(0.3,0,0.1), angular=new Vector3d(0,0,0);
   var results=MultiContactFrictionSolver.solveFriction(contacts,MASS,inertiaBody,orientation,inverse,velocity,angular,
     0.0,0,500,FRICTION,1.0,DT,MultiContactFrictionSolver.FRICTION_SOLVER_ITERATIONS);
   var r=results[0];
   double bound=FRICTION*normal;
   double mag=Math.hypot(r.longitudinal(), r.lateral());
   System.out.println("[MultiStateSolverConvergenceTest] zero/low-load check normal="+normal+" bound="+bound+" longitudinal="+r.longitudinal()+" lateral="+r.lateral()+" mag="+mag);
   assertTrue(Double.isFinite(r.longitudinal()) && Double.isFinite(r.lateral()), "normal="+normal+" must produce finite output, not NaN/Infinity from a near-zero-load division");
   assertTrue(mag <= bound + 1e-6, "normal="+normal+" must respect its own (near-zero) friction bound="+bound+" - got mag="+mag);
  }
 }
}
