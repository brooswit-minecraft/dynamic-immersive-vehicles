package io.github.brooswitminecraft.div.experimental;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/**
 * Direct follow-up to comment 53216's own close: the single seed used in
 * EffectiveMassDampingCandidateTest/ExtendedValidationTest sits near a chaotic flip/no-flip
 * threshold (98bf372 showed the candidate's apparent win reversing as dt got finer at that one
 * seed). This sweeps a RANGE of initial roll seeds at the original DT=.05 to check whether the
 * candidate shifts the flip threshold favorably ON AVERAGE across seeds, or whether 53215's
 * headline number was a favorable draw from a chaotic process with no real average benefit.
 *
 * <p>Still test-only/diagnostic, still isolated from the unreviewed native-contact commits, still
 * the exact same spring/candidate-damper formulas as 29d2ed3/98bf372 - duplicates rather than
 * imports that harness for the same reason EffectiveMassDampingExtendedValidationTest does
 * (hardcoded DT/drive-only assumptions there don't fit a parameterized sweep).
 */
class EffectiveMassDampingSeedSweepTest {
 private static final double MASS=350.0, GRAVITY=11.0, DT=0.05, FRICTION=1.0, SUSPENSION_TRAVEL=0.3, WHEEL_RADIUS=0.375, LENGTH=WHEEL_RADIUS+SUSPENSION_TRAVEL;
 private static final double EQUILIBRIUM_BODY_POSITION_Y=0.803593748062849;
 private static final Vector3d CENTER=new Vector3d(0,0.553593748062849,0.625);
 private static final Vector3d[] REAL_OFFSETS={
  new Vector3d(0.35,0.125,-0.0625), new Vector3d(-0.375,0.125,1.25),
  new Vector3d(0.375,0.125,1.25), new Vector3d(-0.375,0.125,-0.0625)};
 private static final int SOLVER_ITERATIONS=64;
 private static final SelfRightingAssist.Config QUAD_EXAMPLE=new SelfRightingAssist.Config(200,80,600,10,50);
 private static final double RAMP_TICKS=20;
 private static final double FLIP_THRESHOLD_DEGREES=90.0; // past vertical - the real "it tipped over" line, not merely "tilted a lot"

 private static Vector3d anchorLocal(int i) { return new Vector3d(REAL_OFFSETS[i].x, REAL_OFFSETS[i].y+SUSPENSION_TRAVEL*.5, REAL_OFFSETS[i].z).sub(CENTER); }
 private static Vector3d inertiaBody() {
  double a=0.42500001192092896,b=0.4410937510430813,c=1.050000011920929;
  return new Vector3d(MASS/3*(b*b+c*c), MASS/3*(a*a+c*c), MASS/3*(a*a+b*b));
 }
 private static double suspensionSpring(int n) { return MASS*GRAVITY/(n*SUSPENSION_TRAVEL*.5); }
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

 private static double runMaxRoll(boolean candidateDamping, double initialRollDegrees, double initialAngularSeedZ, int ticks) {
  Vector3d inertiaBody=inertiaBody();
  Vector3d velocity=new Vector3d(0,0,0), angular=new Vector3d(0,0,initialAngularSeedZ);
  Quaterniond orientation=new Quaterniond().rotateZ(Math.toRadians(-initialRollDegrees));
  double bodyPositionY=EQUILIBRIUM_BODY_POSITION_Y;
  double spring=suspensionSpring(4);
  double sharedDamper=currentDamper(spring,4);
  double maxRoll=0;
  var rampState=new DriveRateLimiter.State();
  for (int t=0;t<ticks;t++) {
   Quaterniond inverse=new Quaterniond(orientation).conjugate();
   Vector3d velocity0=new Vector3d(velocity), angular0=new Vector3d(angular);
   WheelState[] wheels=new WheelState[4];
   for (int i=0;i<4;i++) {
    wheels[i]=candidateDamping
      ? raycastWheelCandidate(i,bodyPositionY,orientation,velocity0,angular0,spring,inertiaBody,inverse)
      : raycastWheel(i,bodyPositionY,orientation,velocity0,angular0,spring,sharedDamper);
   }
   double maxDeltaPerTick = 8000.0/RAMP_TICKS;
   double shapedDrive = DriveRateLimiter.shape(rampState, 8000, false, maxDeltaPerTick);
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
     false,0,driveForcePerWheel,FRICTION,DT,SOLVER_ITERATIONS);
   velocity.add(new Vector3d(solverVelocity).sub(bf));
   angular.add(new Vector3d(solverAngular).sub(ba));
   double totalNormalLoad=0; for (WheelState w : wheels) if (w.hit()) totalNormalLoad+=w.normal();
   Vector3d bodyForward=orientation.transform(new Vector3d(0,0,1));
   Vector3d bodyUp=orientation.transform(new Vector3d(0,1,0));
   Vector3d bodyRight=new Vector3d(bodyUp).cross(bodyForward);
   Vector3d selfRightTorque=SelfRightingAssist.torque(QUAD_EXAMPLE, bodyForward, bodyUp, bodyRight, angular, true, totalNormalLoad>0);
   if (selfRightTorque.lengthSquared()>0) {
    Vector3d torqueLocal=inverse.transform(new Vector3d(selfRightTorque).mul(DT));
    angular.add(orientation.transform(new Vector3d(torqueLocal.x/inertiaBody.x,torqueLocal.y/inertiaBody.y,torqueLocal.z/inertiaBody.z)));
   }
   bodyPositionY += velocity.y*DT;
   orientation.integrate(DT, angular.x, angular.y, angular.z);
   Vector3d rightNow=orientation.transform(new Vector3d(1,0,0));
   Vector3d upNow=orientation.transform(new Vector3d(0,1,0));
   maxRoll=Math.max(maxRoll, Math.abs(Math.toDegrees(Math.atan2(rightNow.y, upNow.y))));
  }
  return maxRoll;
 }

 /** Sweeps initial roll seed from 5deg to 30deg (the region that spans "settles down" through
  * "the original reported flip") with the same 0.3rad/s angular seed and 80-tick driven window
  * used throughout this investigation's candidate tests, at DT=.05 only (dt sensitivity is
  * already covered separately in ExtendedValidationTest - this sweep isolates the SEED axis).
  * Reports per-seed outcome and an aggregate flip count, rather than asserting a specific
  * direction - the honest answer here is "does the candidate flip less often across this range,"
  * not a single number. */
 @Test void seedSweepReportsFlipRateAcrossInitialRollAngles() {
  int flippedBaseline=0, flippedCandidate=0, candidateBetterCount=0, candidateWorseCount=0;
  for (double seedDegrees=5; seedDegrees<=30; seedDegrees+=2.5) {
   double baseline=runMaxRoll(false, seedDegrees, 0.3, 80);
   double candidate=runMaxRoll(true, seedDegrees, 0.3, 80);
   boolean baselineFlipped=baseline>FLIP_THRESHOLD_DEGREES, candidateFlipped=candidate>FLIP_THRESHOLD_DEGREES;
   if (baselineFlipped) flippedBaseline++;
   if (candidateFlipped) flippedCandidate++;
   if (candidate<baseline) candidateBetterCount++; else if (candidate>baseline) candidateWorseCount++;
   System.out.println("[EffectiveMassDampingSeedSweepTest] seed="+seedDegrees+"deg baseline="+baseline+"deg(flipped="+baselineFlipped
     +") candidate="+candidate+"deg(flipped="+candidateFlipped+")");
  }
  System.out.println("[EffectiveMassDampingSeedSweepTest] AGGREGATE flippedBaseline="+flippedBaseline+" flippedCandidate="+flippedCandidate
    +" candidateBetterCount="+candidateBetterCount+" candidateWorseCount="+candidateWorseCount+" outOf=11seeds");
  org.junit.jupiter.api.Assertions.assertTrue(flippedBaseline>=0 && flippedCandidate>=0, "sanity: counts must be non-negative");
 }
}
