package io.github.brooswitminecraft.div.experimental;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/**
 * Continuation of the EffectiveMassDampingCandidateTest comparison (commit 29d2ed3), covering the
 * rest of comment 53214's explicit ask that single-DT, roll-only comparison did not yet cover:
 * timestep sensitivity, a rotated (combined roll+pitch+yaw) initial pose, and a brake scenario -
 * still "preserve pitch freedom; no new anti-wheelie intervention," still isolated from the
 * unreviewed native-contact commits (never touches {@code groundDeviceCollective}), still
 * test-only/diagnostic - no production wiring in this file.
 *
 * <p>Duplicates (does not import) EffectiveMassDampingCandidateTest's harness because that file's
 * {@code run} hardcodes DT=0.05 and drive-or-nothing; this file generalizes dt and adds braking and
 * a combined-axis initial orientation, while keeping the exact same spring/damper/candidate-damper
 * formulas (same critical-damping-ratio shape, same {@code contactEffectiveMass} operator) so any
 * difference found is attributable to the new scenario axis, not a harness rewrite.
 */
class EffectiveMassDampingExtendedValidationTest {
 private static final double MASS=350.0, GRAVITY=11.0, FRICTION=1.0, SUSPENSION_TRAVEL=0.3, WHEEL_RADIUS=0.375, LENGTH=WHEEL_RADIUS+SUSPENSION_TRAVEL;
 private static final double EQUILIBRIUM_BODY_POSITION_Y=0.803593748062849;
 private static final Vector3d CENTER=new Vector3d(0,0.553593748062849,0.625);
 private static final Vector3d[] REAL_OFFSETS={
  new Vector3d(0.35,0.125,-0.0625), new Vector3d(-0.375,0.125,1.25),
  new Vector3d(0.375,0.125,1.25), new Vector3d(-0.375,0.125,-0.0625)};
 private static final int SOLVER_ITERATIONS=64;
 private static final SelfRightingAssist.Config QUAD_EXAMPLE=new SelfRightingAssist.Config(200,80,600,10,50);
 private static final double RAMP_SECONDS=20*0.05; // the shipped 20-tick-at-DT=.05 ramp, expressed physically so it scales with dt
 private static final double BRAKE_FORCE=12000.0;

 private static Vector3d anchorLocal(int i) { return new Vector3d(REAL_OFFSETS[i].x, REAL_OFFSETS[i].y+SUSPENSION_TRAVEL*.5, REAL_OFFSETS[i].z).sub(CENTER); }
 private static Vector3d inertiaBody() {
  double a=0.42500001192092896,b=0.4410937510430813,c=1.050000011920929;
  return new Vector3d(MASS/3*(b*b+c*c), MASS/3*(a*a+c*c), MASS/3*(a*a+b*b));
 }
 private static double suspensionSpring(int n) { return MASS*GRAVITY/(n*SUSPENSION_TRAVEL*.5); } // UNCHANGED - static rest-sag stays exactly as shipped
 private static double currentDamper(double spring, int n) { return 2*Math.sqrt(spring*MASS/n)*.7; }
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
  double normal=Math.min(MASS*GRAVITY, Math.max(0, compression*spring - localVelocity.dot(up)*damper));
  return new WheelState(up,true,normal,point,forward,right);
 }
 private static WheelState raycastWheelCandidate(int i, double bodyPositionY, Quaterniond orientation, Vector3d velocity, Vector3d angular, double spring, Vector3d inertiaBody, Quaterniond inverse) {
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
  double effectiveMass=MultiContactFrictionSolver.contactEffectiveMass(MASS, point, up, inverse, inertiaBody);
  double damper=2*Math.sqrt(spring*effectiveMass)*.7;
  double normal=Math.min(MASS*GRAVITY, Math.max(0, compression*spring - localVelocity.dot(up)*damper));
  return new WheelState(up,true,normal,point,forward,right);
 }

 private record Result(double maxRollDegrees, double maxPitchDegrees, double peakAngularMag, double finalFrontNormal, double finalRearNormal) {}

 /** @param initialRoll/@param initialPitch/@param initialYaw a combined-axis initial orientation,
  *  not roll-only as the first comparison used. @param braking replaces drive when true (mutually
  *  exclusive scenario, same as the real control schedule never commanding both at once). */
 private static Result run(boolean candidateDamping, double dt, boolean drive, boolean braking,
     double initialRoll, double initialPitch, double initialYaw, double initialAngularSeedZ, int ticks) {
  Vector3d inertiaBody=inertiaBody();
  Vector3d velocity=new Vector3d(0,0,0), angular=new Vector3d(0,0,initialAngularSeedZ);
  Quaterniond orientation=new Quaterniond().rotateZ(Math.toRadians(-initialRoll)).rotateX(Math.toRadians(initialPitch)).rotateY(Math.toRadians(initialYaw));
  double bodyPositionY=EQUILIBRIUM_BODY_POSITION_Y;
  double spring=suspensionSpring(4);
  double sharedDamper=currentDamper(spring,4);
  double maxRoll=0, maxPitch=0, peakAngularMag=0;
  double finalFrontNormal=0, finalRearNormal=0;
  var rampState=new DriveRateLimiter.State();
  double maxDeltaPerTick=8000.0/RAMP_SECONDS*dt;
  for (int t=0;t<ticks;t++) {
   Quaterniond inverse=new Quaterniond(orientation).conjugate();
   Vector3d velocity0=new Vector3d(velocity), angular0=new Vector3d(angular);
   WheelState[] wheels=new WheelState[4];
   for (int i=0;i<4;i++) {
    wheels[i]=candidateDamping
      ? raycastWheelCandidate(i,bodyPositionY,orientation,velocity0,angular0,spring,inertiaBody,inverse)
      : raycastWheel(i,bodyPositionY,orientation,velocity0,angular0,spring,sharedDamper);
   }
   double commanded = (drive && !braking) ? 8000 : 0;
   double shapedDrive = DriveRateLimiter.shape(rampState, commanded, braking, maxDeltaPerTick);
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
   for (int k=0;k<active.size();k++) { WheelState w=active.get(k); contacts[k]=new MultiContactFrictionSolver.FrictionContact(w.point(),w.forward(),w.right(),w.normal(),1.0,(braking?1.0:0.0)); }
   Vector3d bf=new Vector3d(solverVelocity), ba=new Vector3d(solverAngular);
   double driveForcePerWheel=active.isEmpty()?0:shapedDrive/4.0;
   MultiContactFrictionSolver.solveFriction(contacts,MASS,inertiaBody,orientation,inverse,solverVelocity,solverAngular,braking?BRAKE_FORCE/4.0:0,driveForcePerWheel,FRICTION,1.0,dt,SOLVER_ITERATIONS);
   velocity.add(new Vector3d(solverVelocity).sub(bf));
   angular.add(new Vector3d(solverAngular).sub(ba));
   double totalNormalLoad=0; for (WheelState w : wheels) if (w.hit()) totalNormalLoad+=w.normal();
   Vector3d bodyForward=orientation.transform(new Vector3d(0,0,1));
   Vector3d bodyUp=orientation.transform(new Vector3d(0,1,0));
   Vector3d bodyRight=new Vector3d(bodyUp).cross(bodyForward);
   Vector3d selfRightTorque=SelfRightingAssist.torque(QUAD_EXAMPLE, bodyForward, bodyUp, bodyRight, angular, true, totalNormalLoad>0);
   if (selfRightTorque.lengthSquared()>0) {
    Vector3d torqueLocal=inverse.transform(new Vector3d(selfRightTorque).mul(dt));
    angular.add(orientation.transform(new Vector3d(torqueLocal.x/inertiaBody.x,torqueLocal.y/inertiaBody.y,torqueLocal.z/inertiaBody.z)));
   }
   bodyPositionY += velocity.y*dt;
   orientation.integrate(dt, angular.x, angular.y, angular.z);
   peakAngularMag=Math.max(peakAngularMag, angular.length());
   Vector3d upNow=orientation.transform(new Vector3d(0,1,0));
   Vector3d rightNow=orientation.transform(new Vector3d(1,0,0));
   maxRoll=Math.max(maxRoll, Math.abs(Math.toDegrees(Math.atan2(rightNow.y, upNow.y))));
   maxPitch=Math.max(maxPitch, Math.abs(Math.toDegrees(Math.asin(Math.max(-1,Math.min(1, orientation.transform(new Vector3d(0,0,1)).y))))));
   finalFrontNormal=(wheels[1].hit()?wheels[1].normal():0)+(wheels[2].hit()?wheels[2].normal():0);
   finalRearNormal=(wheels[0].hit()?wheels[0].normal():0)+(wheels[3].hit()?wheels[3].normal():0);
  }
  return new Result(maxRoll, maxPitch, peakAngularMag, finalFrontNormal, finalRearNormal);
 }

 /** Same scenario (seed 15deg roll + 0.3rad/s angular, driven, recontact) that produced the
  * headline 179.35deg->35.06deg result at DT=.05 - checked at the two finer substeps the rest of
  * this investigation's timestep-sensitivity tests already use, with iteration count held fixed.
  *
  * <p>REAL FINDING, not asserted away: the improvement does NOT hold. dt=.05: baseline=179.35deg
  * (flipped) vs candidate=35.06deg (big win). dt=.025: baseline=178.08deg vs candidate=154.48deg
  * (smaller win, both still heavily tilted). dt=.0125: baseline=51.91deg (did NOT flip) vs
  * candidate=179.16deg (DID flip) - the direction REVERSES at the finest timestep tested. This is
  * a genuine chaotic-sensitivity result in a bistable (flip/no-flip) scenario, not a harness bug -
  * both runs use the identical control schedule and iteration count, differing only in dt and the
  * damping formula. It means the single-DT=.05 result reported in comment 53215 is NOT shown to be
  * timestep-robust, and this candidate's benefit cannot be trusted at face value without further
  * investigation (e.g. many seeds/scenarios, not one knife-edge seed near a flip/no-flip boundary).
  * Kept as a diagnostic (prints all three, asserts only finiteness/stability) rather than a
  * pass/fail gate on a single seed that may simply be near a chaotic threshold either way. */
 @Test void drivenRecontactImprovementAcrossTimesteps() {
  for (double dt : new double[]{0.05, 0.025, 0.0125}) {
   int ticks=(int)Math.round(80*0.05/dt);
   var baseline=run(false, dt, true, false, 15, 0, 0, 0.3, ticks);
   var candidate=run(true, dt, true, false, 15, 0, 0, 0.3, ticks);
   System.out.println("[EffectiveMassDampingExtendedValidationTest] drivenRecontact dt="+dt
     +" baseline(maxRoll="+baseline.maxRollDegrees()+" maxPitch="+baseline.maxPitchDegrees()+")"
     +" candidate(maxRoll="+candidate.maxRollDegrees()+" maxPitch="+candidate.maxPitchDegrees()+")");
   org.junit.jupiter.api.Assertions.assertTrue(Double.isFinite(candidate.maxRollDegrees()) && Double.isFinite(baseline.maxRollDegrees()),
     "dt="+dt+": both runs must remain numerically stable (no NaN/divergence) regardless of which one tilts more");
  }
 }

 /** Comment 53214 asked specifically for "rotated poses," not just a pure-roll seed - this
  * combines roll+pitch+yaw together (a tipped, nose-up, yawed-off-axis start) so the comparison
  * isn't accidentally only validated along one axis. */
 @Test void rotatedPoseCombinedRollPitchYawComparison() {
  var baseline=run(false, 0.05, true, false, 18, -12, 35, 0.3, 80);
  var candidate=run(true, 0.05, true, false, 18, -12, 35, 0.3, 80);
  System.out.println("[EffectiveMassDampingExtendedValidationTest] rotatedPose baseline(maxRoll="+baseline.maxRollDegrees()
    +" maxPitch="+baseline.maxPitchDegrees()+" peakAngular="+baseline.peakAngularMag()+")"
    +" candidate(maxRoll="+candidate.maxRollDegrees()+" maxPitch="+candidate.maxPitchDegrees()+" peakAngular="+candidate.peakAngularMag()+")");
  org.junit.jupiter.api.Assertions.assertTrue(Double.isFinite(candidate.maxRollDegrees()) && Double.isFinite(candidate.maxPitchDegrees()),
    "candidate must remain numerically stable (no NaN/divergence) from a combined-axis rotated start");
 }

 /** Comment 53214 asked for a brake scenario too, not only drive - braking is the OTHER real
  * control input that changes wheel loading dynamically (weight transfer forward under braking,
  * the opposite direction from drive's rearward transfer during a wheelie). Checks the candidate
  * does not regress braking behavior while it improves the drive/recontact case. */
 @Test void brakeScenarioDoesNotRegressRelativeToBaseline() {
  var baseline=run(false, 0.05, false, true, 15, 0, 0, 0.3, 80);
  var candidate=run(true, 0.05, false, true, 15, 0, 0, 0.3, 80);
  System.out.println("[EffectiveMassDampingExtendedValidationTest] brake baseline(maxRoll="+baseline.maxRollDegrees()
    +" peakAngular="+baseline.peakAngularMag()+") candidate(maxRoll="+candidate.maxRollDegrees()+" peakAngular="+candidate.peakAngularMag()+")");
  org.junit.jupiter.api.Assertions.assertTrue(candidate.maxRollDegrees() <= baseline.maxRollDegrees() + 1.0,
    "candidate must not make braking-induced roll measurably WORSE than baseline (allowing 1deg slack for numerical noise) - got candidate="
      +candidate.maxRollDegrees()+" baseline="+baseline.maxRollDegrees());
 }
}
