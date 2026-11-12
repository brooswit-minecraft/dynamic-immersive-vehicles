package io.github.brooswitminecraft.div.experimental;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/**
 * Ticket comment 53127's requested first step: "compare corrected whole trajectories at
 * 4/8/16/32/64 passes, both orders; record max tilt, first support loss, angular peaks, and the
 * release/reapplication interval." Diagnostic only - no production code touched, no claim of
 * actual rollover causality (53127: the earlier frozen-t109 comparison used a HARNESS-generated
 * state at that tick number, not the real captured production state at real tick109, and this
 * harness's own corrected replay already has the opposite sign from the real measured roll - see
 * {@code WindowTwoControlScheduleReplayTest}).
 *
 * <p>Also fixes 53127's methodological correction: "angularDeltaZ currently means WORLD Z, not
 * necessarily body roll." TILT here is the coordinate-independent {@code acos(up.worldUp)}
 * already used elsewhere in this investigation; ROLL RATE is the angular velocity vector
 * projected onto the vehicle's own current body-forward axis ({@code angular.dot(forward)}), not
 * a raw world-frame component - the two agree only while the body is still near its initial
 * orientation, which this long a trajectory does not stay in.
 */
class WindowTwoIterationCountComparisonTest {
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
 private record TickSample(int tick, double tiltDegrees, double rollRateBodyForward, Vector3d angular, double frontNormal, double rearNormal) {}

 private static java.util.List<TickSample> run(int[] order, int iterations, int ticks) {
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
   for (int k=0;k<activeOrder.size();k++) { WheelState w=wheels[activeOrder.get(k)]; contacts[k]=new MultiContactFrictionSolver.FrictionContact(w.point(),w.forward(),w.right(),w.normal(),1.0); }
   Vector3d bf=new Vector3d(solverVelocity), ba=new Vector3d(solverAngular);
   double driveForcePerWheel=activeOrder.isEmpty()?0:shapedDrive/4.0;
   MultiContactFrictionSolver.solveFriction(contacts,MASS,inertiaBody,orientation,inverse,solverVelocity,solverAngular,
     braking,BRAKE_FORCE/4.0,driveForcePerWheel,FRICTION,1.0,DT,iterations);
   velocity.add(new Vector3d(solverVelocity).sub(bf));
   angular.add(new Vector3d(solverAngular).sub(ba));
   double frontNormal=(wheels[1].hit()?wheels[1].normal():0) + (wheels[2].hit()?wheels[2].normal():0);
   double rearNormal=(wheels[0].hit()?wheels[0].normal():0) + (wheels[3].hit()?wheels[3].normal():0);
   bodyPositionY += velocity.y*DT;
   orientation.integrate(DT, angular.x, angular.y, angular.z);
   Vector3d upNow=orientation.transform(new Vector3d(0,1,0));
   Vector3d forwardNow=orientation.transform(new Vector3d(0,0,1));
   double tiltDegrees=Math.toDegrees(Math.acos(Math.max(-1,Math.min(1,upNow.y))));
   double rollRateBodyForward=angular.dot(forwardNow);
   samples.add(new TickSample(t, tiltDegrees, rollRateBodyForward, new Vector3d(angular), frontNormal, rearNormal));
  }
  return samples;
 }

 @Test void diagnosticIterationCountAndOrderComparison() {
  int[] iterationCounts={4,8,16,32,64};
  int[][] orders={{0,1,2,3},{3,1,2,0}};
  String[] orderNames={"normal","reversed"};
  for (int oi=0; oi<orders.length; oi++) {
   for (int iterations : iterationCounts) {
    long startNanos=System.nanoTime();
    var samples=run(orders[oi], iterations, 117);
    long elapsedNanos=System.nanoTime()-startNanos;
    double maxTilt=0; int firstFrontLoss=-1; double peakAngularMag=0;
    for (TickSample s : samples) {
     maxTilt=Math.max(maxTilt, s.tiltDegrees());
     peakAngularMag=Math.max(peakAngularMag, s.angular().length());
     if (firstFrontLoss<0 && s.frontNormal()==0) firstFrontLoss=s.tick();
    }
    double maxTiltInReleaseReapplyWindow=0;
    for (TickSample s : samples) if (s.tick()>=98 && s.tick()<=121) maxTiltInReleaseReapplyWindow=Math.max(maxTiltInReleaseReapplyWindow, s.tiltDegrees());
    System.out.println("[WindowTwoIterationCountComparisonTest] order="+orderNames[oi]+" iterations="+iterations
      +" maxTilt="+maxTilt+"deg firstFrontSupportLoss="+firstFrontLoss+" peakAngularMag="+peakAngularMag+"rad/s"
      +" maxTiltInRelease98to121="+maxTiltInReleaseReapplyWindow+"deg wallNanosFor117Ticks="+elapsedNanos);
   }
  }
 }
}
