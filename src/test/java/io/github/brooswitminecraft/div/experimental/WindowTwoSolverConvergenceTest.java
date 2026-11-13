package io.github.brooswitminecraft.div.experimental;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/**
 * Ticket comment 53125's explicit methodological correction to the wheel-order-reversal
 * diagnostic in {@code WindowTwoControlScheduleReplayTest}: a terminal-roll difference after a
 * whole 116-tick trajectory "can reflect nonlinear trajectory divergence, not proof four
 * iterations cause the observed flop." This isolates the two possible explanations by freezing
 * ONE tick's actual contacts/velocity/angular state (reached by replaying the real schedule
 * normally up to that tick) and then calling {@code MultiContactFrictionSolver.solveFriction}
 * directly on that SAME frozen input at several iteration counts and both wheel orders - so any
 * difference found here is attributable only to iteration count and/or array order within a
 * single tick's solve, never to two trajectories having already diverged before this point.
 *
 * <p>Frozen at t=109 - the real capture's own reported peak-divergence tick ("roll ... to
 * -37.3deg at109", comment 53122). Diagnostic only, no production code touched.
 */
class WindowTwoSolverConvergenceTest {
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

 /** Replays the real schedule normally (array order {0,1,2,3}, the shipped 4-iteration solver)
  * up to (not including) {@code freezeAtTick}, then stages tick {@code freezeAtTick}'s own
  * vertical/suspension impulses (exactly as production's Phase1/Phase2) and returns the frozen
  * state immediately BEFORE the friction solve - i.e. the exact input production's own
  * {@code solveFriction} call would receive at that tick. */
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
   for (int k=0;k<active.size();k++) { WheelState w=active.get(k); contacts[k]=new MultiContactFrictionSolver.FrictionContact(w.point(),w.forward(),w.right(),w.normal(),1.0,(braking?1.0:0.0)); }
   Vector3d bf=new Vector3d(solverVelocity), ba=new Vector3d(solverAngular);
   double driveForcePerWheel=active.isEmpty()?0:shapedDrive/4.0;
   MultiContactFrictionSolver.solveFriction(contacts,MASS,inertiaBody,orientation,inverse,solverVelocity,solverAngular,
     BRAKE_FORCE/4.0,driveForcePerWheel,FRICTION,1.0,DT,MultiContactFrictionSolver.FRICTION_SOLVER_ITERATIONS);
   velocity.add(new Vector3d(solverVelocity).sub(bf));
   angular.add(new Vector3d(solverAngular).sub(ba));
   bodyPositionY += velocity.y*DT;
   orientation.integrate(DT, angular.x, angular.y, angular.z);
  }
  // Tick freezeAtTick itself: stage vertical impulses only, stop before the friction solve.
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

 private record ConvergenceSample(int iterations, double netLongitudinal, double netLateral, double angularDeltaZ) {}
 private static ConvergenceSample solveFrozen(FrozenState frozen, int[] order, int iterations) {
  java.util.List<Integer> activeOrder=new java.util.ArrayList<>();
  for (int idx : order) if (frozen.wheels()[idx].hit()) activeOrder.add(idx);
  var contacts=new MultiContactFrictionSolver.FrictionContact[activeOrder.size()];
  for (int k=0;k<activeOrder.size();k++) { WheelState w=frozen.wheels()[activeOrder.get(k)]; contacts[k]=new MultiContactFrictionSolver.FrictionContact(w.point(),w.forward(),w.right(),w.normal(),1.0,(frozen.braking()?1.0:0.0)); }
  Vector3d velocity=new Vector3d(frozen.solverVelocity()), angular=new Vector3d(frozen.solverAngular());
  Vector3d angularBefore=new Vector3d(angular);
  var results=MultiContactFrictionSolver.solveFriction(contacts,MASS,inertiaBody(),frozen.orientation(),frozen.inverse(),velocity,angular,
    BRAKE_FORCE/4.0,frozen.driveForcePerWheel(),FRICTION,1.0,DT,iterations);
  double netLong=0, netLat=0; for (var r:results) { netLong+=r.longitudinal(); netLat+=r.lateral(); }
  return new ConvergenceSample(iterations, netLong, netLat, angular.z-angularBefore.z);
 }

 @Test void frozenStateConvergenceAcrossIterationsAndWheelOrder() {
  FrozenState frozen = freeze(109);
  int[] normalOrder={0,1,2,3}, reversedOrder={3,1,2,0};
  int[] iterationCounts={4,8,16,32};
  System.out.println("[WindowTwoSolverConvergenceTest] frozen at t=109, active wheels: "
    +java.util.Arrays.stream(new int[]{0,1,2,3}).filter(i->frozen.wheels()[i].hit()).count()+"/4, braking="+frozen.braking()+" driveForcePerWheel="+frozen.driveForcePerWheel());
  for (int iterations : iterationCounts) {
   var normal = solveFrozen(frozen, normalOrder, iterations);
   var reversed = solveFrozen(frozen, reversedOrder, iterations);
   System.out.println("[WindowTwoSolverConvergenceTest] iterations="+iterations
     +" normal(netLong="+normal.netLongitudinal()+" netLat="+normal.netLateral()+" angularDeltaZ="+normal.angularDeltaZ()+")"
     +" reversed(netLong="+reversed.netLongitudinal()+" netLat="+reversed.netLateral()+" angularDeltaZ="+reversed.angularDeltaZ()+")"
     +" |order diff angularDeltaZ|="+Math.abs(normal.angularDeltaZ()-reversed.angularDeltaZ()));
  }
  var at4 = solveFrozen(frozen, normalOrder, 4);
  var at32 = solveFrozen(frozen, normalOrder, 32);
  System.out.println("[WindowTwoSolverConvergenceTest] normal-order convergence 4->32 iterations, |angularDeltaZ diff|="+Math.abs(at4.angularDeltaZ()-at32.angularDeltaZ()));
 }
}
