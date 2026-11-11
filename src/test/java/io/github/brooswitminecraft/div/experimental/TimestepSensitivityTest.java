package io.github.brooswitminecraft.div.experimental;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/**
 * Diagnostic-only timestep-sensitivity check requested in ticket comment 53138, run while the
 * 64-iteration/20-tick-ramp candidate is held stable pending the real install's own runtime
 * verification (NOT yet locally confirmed - comment 53138 is explicit that only the file-level
 * install was checked so far, not an actual running session).
 *
 * <p>Compares the real corrected window2 control schedule (see
 * {@code WindowTwoControlScheduleReplayTest}) at DT=.05/.025/.0125, with the solver iteration
 * count held FIXED at 64 (the already-measured near-converged candidate) across all three - so
 * any difference found is attributable to the integration timestep itself, not to conflating it
 * with insufficient friction-solve convergence (comment 53138's explicit "distinguish full
 * recalc/substep suspension from merely more friction iterations": this harness calls the raycast
 * + suspension force + friction solve ONCE per timestep, i.e. a genuine substep of the WHOLE
 * physics - not an unrelated change to iteration count at a fixed outer tick).
 *
 * <p>The control schedule itself is defined in PHYSICAL SECONDS (converted once from the real
 * captured tick numbers via the real game's own DT=0.05), then re-discretized into ticks for
 * each test DT - so halving DT correctly doubles the tick count over the same real-world
 * duration, rather than silently stretching or compressing the throttle/brake/ramp timing
 * (comment 53138's explicit concern). The 1-second drive-rate-ramp is likewise expressed as
 * N-per-SECOND, converted to N-per-tick via the test's own DT, not a fixed per-tick constant.
 *
 * <p>Idealized-harness limitations, same as every other test in this investigation using this
 * suspension/raycast model: approximated spring/damper suspension, no real terrain variation, no
 * steering (stopped before the real "steer first117"), not a bit-exact replica of production's
 * own staging.
 */
class TimestepSensitivityTest {
 private static final double MASS=350.0, GRAVITY=11.0, FRICTION=1.0, SUSPENSION_TRAVEL=0.3, WHEEL_RADIUS=0.375, LENGTH=WHEEL_RADIUS+SUSPENSION_TRAVEL, BRAKE_FORCE=12000.0, MAX_DRIVE=8000.0;
 private static final double EQUILIBRIUM_BODY_POSITION_Y=0.803593748062849;
 private static final Vector3d HALF_EXTENTS=new Vector3d(0.42500001192092896,0.4410937510430813,1.050000011920929);
 private static final Vector3d CENTER=new Vector3d(0,0.553593748062849,0.625);
 private static final Vector3d[] REAL_OFFSETS={
  new Vector3d(0.35,0.125,-0.0625), new Vector3d(-0.375,0.125,1.25),
  new Vector3d(0.375,0.125,1.25), new Vector3d(-0.375,0.125,-0.0625)};
 private static final boolean[] IS_FRONT={false,true,true,false};
 private static Vector3d anchorLocal(int i) { return new Vector3d(REAL_OFFSETS[i].x, REAL_OFFSETS[i].y+SUSPENSION_TRAVEL*.5, REAL_OFFSETS[i].z).sub(CENTER); }
 private static Vector3d inertiaBody() {
  double a=HALF_EXTENTS.x,b=HALF_EXTENTS.y,c=HALF_EXTENTS.z;
  return new Vector3d(MASS/3*(b*b+c*c), MASS/3*(a*a+c*c), MASS/3*(a*a+b*b));
 }
 // Spring/damper are physical constants (N/m, N*s/m) - independent of integration timestep;
 // no DT-dependence here, unlike the per-tick impulse/integration steps below.
 private static double suspensionSpring(int n) { return MASS*GRAVITY/(n*SUSPENSION_TRAVEL*.5); }
 private static double suspensionDamper(double spring, int n) { return 2*Math.sqrt(spring*MASS/n)*.7; }
 private static double suspensionForce(double c, double v, double spring, double damper) { return Math.max(0, c*spring-v*damper); }
 private record WheelState(Vector3d up, boolean hit, double normal, Vector3d point, Vector3d forward, Vector3d right, boolean front) {}
 private static WheelState raycastWheel(int i, double bodyPositionY, Quaterniond orientation, Vector3d velocity, Vector3d angular, double spring, double damper) {
  Vector3d anchorWorld=orientation.transform(new Vector3d(anchorLocal(i)));
  Vector3d up=orientation.transform(new Vector3d(0,1,0));
  Vector3d forward=orientation.transform(new Vector3d(0,0,1));
  Vector3d right=new Vector3d(up).cross(forward);
  double startY=bodyPositionY+anchorWorld.y;
  double tt=startY/(LENGTH*up.y);
  if (!(tt>=0&&tt<=1)||!Double.isFinite(tt)) return new WheelState(up,false,0,null,forward,right,IS_FRONT[i]);
  double compression=LENGTH*(1-tt);
  Vector3d point=new Vector3d(anchorWorld).fma(-tt*LENGTH,up);
  Vector3d localVelocity=new Vector3d(angular).cross(anchorWorld).add(velocity);
  double normal=Math.min(MASS*GRAVITY, suspensionForce(compression, localVelocity.dot(up), spring, damper));
  return new WheelState(up,true,normal,point,forward,right,IS_FRONT[i]);
 }

 // Real window2 control schedule (comment 53123, fixed per 53125), converted ONCE from the real
 // captured tick numbers into physical seconds via the real game's own fixed DT.
 private static final double REAL_GAME_DT=0.05;
 private static boolean brakingAtSeconds(double t) {
  return (t>=44*REAL_GAME_DT && t<=48*REAL_GAME_DT) || (t>=61*REAL_GAME_DT && t<=66*REAL_GAME_DT);
 }
 private static double commandedDriveAtSeconds(double t) {
  if ((t>=50*REAL_GAME_DT && t<=60*REAL_GAME_DT) || (t>=67*REAL_GAME_DT && t<=97*REAL_GAME_DT) || (t>=105*REAL_GAME_DT && t<=121*REAL_GAME_DT)) return MAX_DRIVE;
  return 0;
 }
 private static final double RAMP_SECONDS=1.0; // the candidate's own 20-tick-at-DT=.05 ramp, expressed physically
 private static final double TOTAL_SECONDS=117*REAL_GAME_DT; // same real-world duration as the 117-tick window2 replay

 private record Result(double maxTiltDegrees, Double firstFrontLossSeconds, double peakAngularMag, double finalKineticEnergy) {}

 private static Result run(double dt, int iterations) {
  int ticks=(int)Math.round(TOTAL_SECONDS/dt);
  double maxDeltaPerSecond=MAX_DRIVE/RAMP_SECONDS;
  double maxDeltaPerTick=maxDeltaPerSecond*dt; // physical rate converted to this DT's own per-tick delta - NOT a fixed per-tick constant
  Vector3d inertiaBody=inertiaBody();
  Vector3d velocity=new Vector3d(0,0,0), angular=new Vector3d(0,0,0);
  Quaterniond orientation=new Quaterniond();
  double bodyPositionY=EQUILIBRIUM_BODY_POSITION_Y;
  double spring=suspensionSpring(4), damper=suspensionDamper(spring,4);
  var rampState=new DriveRateLimiter.State();
  double maxTilt=0; Double firstFrontLossSeconds=null; double peakAngularMag=0;
  for (int t=0;t<ticks;t++) {
   double tSeconds=t*dt;
   boolean braking=brakingAtSeconds(tSeconds);
   double shapedDrive=DriveRateLimiter.shape(rampState, commandedDriveAtSeconds(tSeconds), braking, maxDeltaPerTick);
   Quaterniond inverse=new Quaterniond(orientation).conjugate();
   Vector3d velocity0=new Vector3d(velocity), angular0=new Vector3d(angular);
   WheelState[] wheels=new WheelState[4];
   for (int i=0;i<4;i++) wheels[i]=raycastWheel(i,bodyPositionY,orientation,velocity0,angular0,spring,damper);
   Vector3d solverVelocity=new Vector3d(velocity0), solverAngular=new Vector3d(angular0);
   for (WheelState w : wheels) {
    if (!w.hit()) continue;
    Vector3d vi=new Vector3d(w.up()).mul(w.normal()*dt);
    solverVelocity.add(new Vector3d(vi).div(MASS));
    Vector3d tb=inverse.transform(new Vector3d(w.point()).cross(vi));
    solverAngular.add(orientation.transform(new Vector3d(tb.x/inertiaBody.x,tb.y/inertiaBody.y,tb.z/inertiaBody.z)));
   }
   velocity.add(new Vector3d(0,-GRAVITY*dt,0));
   velocity.add(new Vector3d(solverVelocity).sub(velocity0));
   angular.add(new Vector3d(solverAngular).sub(angular0));
   java.util.List<WheelState> active=new java.util.ArrayList<>();
   for (WheelState w : wheels) if (w.hit()) active.add(w);
   var contacts=new MultiContactFrictionSolver.FrictionContact[active.size()];
   for (int k=0;k<active.size();k++) { WheelState w=active.get(k); contacts[k]=new MultiContactFrictionSolver.FrictionContact(w.point(),w.forward(),w.right(),w.normal(),1.0); }
   Vector3d bf=new Vector3d(solverVelocity), ba=new Vector3d(solverAngular);
   double driveForcePerWheel=active.isEmpty()?0:shapedDrive/4.0;
   MultiContactFrictionSolver.solveFriction(contacts,MASS,inertiaBody,orientation,inverse,solverVelocity,solverAngular,
     braking,BRAKE_FORCE/4.0,driveForcePerWheel,FRICTION,dt,iterations);
   velocity.add(new Vector3d(solverVelocity).sub(bf));
   angular.add(new Vector3d(solverAngular).sub(ba));
   double frontNormal=(wheels[1].hit()?wheels[1].normal():0)+(wheels[2].hit()?wheels[2].normal():0);
   if (firstFrontLossSeconds==null && frontNormal==0) firstFrontLossSeconds=tSeconds;
   bodyPositionY += velocity.y*dt;
   orientation.integrate(dt, angular.x, angular.y, angular.z);
   Vector3d upNow=orientation.transform(new Vector3d(0,1,0));
   maxTilt=Math.max(maxTilt, Math.toDegrees(Math.acos(Math.max(-1,Math.min(1,upNow.y)))));
   peakAngularMag=Math.max(peakAngularMag, angular.length());
  }
  Quaterniond inverseFinal=new Quaterniond(orientation).conjugate();
  Vector3d angularBodyFinal=inverseFinal.transform(new Vector3d(angular));
  double rotationalKE=0.5*(angularBodyFinal.x*angularBodyFinal.x*inertiaBody.x + angularBodyFinal.y*angularBodyFinal.y*inertiaBody.y + angularBodyFinal.z*angularBodyFinal.z*inertiaBody.z);
  double translationalKE=0.5*MASS*velocity.lengthSquared();
  return new Result(maxTilt, firstFrontLossSeconds, peakAngularMag, translationalKE+rotationalKE);
 }

 @Test void diagnosticTimestepSensitivityAtConvergedIterations() {
  int convergedIterations=64; // held fixed across all DT so only the timestep itself varies
  double[] timesteps={0.05, 0.025, 0.0125};
  for (double dt : timesteps) {
   var r=run(dt, convergedIterations);
   System.out.println("[TimestepSensitivityTest] dt="+dt+" ticks="+(int)Math.round(TOTAL_SECONDS/dt)
     +" maxTilt="+r.maxTiltDegrees()+"deg firstFrontLossSeconds="+r.firstFrontLossSeconds()
     +" peakAngularMag="+r.peakAngularMag()+"rad/s finalKineticEnergy="+r.finalKineticEnergy()+"J");
  }
 }
}
