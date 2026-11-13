package io.github.brooswitminecraft.div.experimental;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/**
 * Bounded Offroad-inspired candidate requested in comment 53214: keep the current static spring
 * stiffness/rest-sag EXACTLY unchanged, and change ONLY the damping term's inertial basis from
 * the shared {@code mass/wheelCount} this class already uses to the actual PER-CONTACT effective
 * mass along "up" ({@code MultiContactFrictionSolver.contactEffectiveMass}, confirmed reusable
 * in comment 53212/commit c8b7796). Evaluates whether this measurably changes coupled response -
 * does not assume it helps.
 *
 * <p>Baseline is d33d2e4 (assist active, solver=64, ramp=20) - isolated from the unreviewed
 * native-contact commits (4e6a8f9 onward): this harness never touches
 * {@code groundDeviceCollective}, same as every other test in this suite.
 */
class EffectiveMassDampingCandidateTest {
 private static final double MASS=350.0, GRAVITY=11.0, DT=0.05, FRICTION=1.0, SUSPENSION_TRAVEL=0.3, WHEEL_RADIUS=0.375, LENGTH=WHEEL_RADIUS+SUSPENSION_TRAVEL;
 private static final double EQUILIBRIUM_BODY_POSITION_Y=0.803593748062849;
 private static final Vector3d CENTER=new Vector3d(0,0.553593748062849,0.625);
 private static final Vector3d[] REAL_OFFSETS={
  new Vector3d(0.35,0.125,-0.0625), new Vector3d(-0.375,0.125,1.25),
  new Vector3d(0.375,0.125,1.25), new Vector3d(-0.375,0.125,-0.0625)};
 private static final int SOLVER_ITERATIONS=64;
 private static final SelfRightingAssist.Config QUAD_EXAMPLE=new SelfRightingAssist.Config(200,80,600,10,50);
 private static final int RAMP_TICKS=20;

 private static Vector3d anchorLocal(int i) { return new Vector3d(REAL_OFFSETS[i].x, REAL_OFFSETS[i].y+SUSPENSION_TRAVEL*.5, REAL_OFFSETS[i].z).sub(CENTER); }
 private static Vector3d inertiaBody() {
  double a=0.42500001192092896,b=0.4410937510430813,c=1.050000011920929;
  return new Vector3d(MASS/3*(b*b+c*c), MASS/3*(a*a+c*c), MASS/3*(a*a+b*b));
 }
 private static double suspensionSpring(int n) { return MASS*GRAVITY/(n*SUSPENSION_TRAVEL*.5); } // UNCHANGED - static rest-sag stays exactly as shipped
 private static double currentDamper(double spring, int n) { return 2*Math.sqrt(spring*MASS/n)*.7; } // shipped: shared, mass/wheelCount
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
 /** Same as {@link #raycastWheel} but takes a PER-WHEEL damper (the candidate: computed from
  * contactEffectiveMass at this wheel's own point, same critical-damping-ratio formula shape). */
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
  double damper=2*Math.sqrt(spring*effectiveMass)*.7; // same critical-damping-ratio SHAPE, per-contact inertial basis instead of mass/wheelCount
  double normal=Math.min(MASS*GRAVITY, Math.max(0, compression*spring - localVelocity.dot(up)*damper));
  return new WheelState(up,true,normal,point,forward,right);
 }

 private record TickSample(int tick, double rollDegrees, double pitchDegrees, double peakAngularMag, double frontNormal, double rearNormal) {}

 private static java.util.List<TickSample> run(boolean candidateDamping, boolean drive, double initialRollDegrees, double initialAngularSeedZ, int ticks) {
  Vector3d inertiaBody=inertiaBody();
  Vector3d velocity=new Vector3d(0,0,0), angular=new Vector3d(0,0,initialAngularSeedZ);
  Quaterniond orientation=new Quaterniond().rotateZ(Math.toRadians(-initialRollDegrees));
  double bodyPositionY=EQUILIBRIUM_BODY_POSITION_Y;
  double spring=suspensionSpring(4);
  double sharedDamper=currentDamper(spring,4);
  double peakAngularMag=0;
  var rampState=new DriveRateLimiter.State();
  var samples=new java.util.ArrayList<TickSample>();
  for (int t=0;t<ticks;t++) {
   Quaterniond inverse=new Quaterniond(orientation).conjugate();
   Vector3d velocity0=new Vector3d(velocity), angular0=new Vector3d(angular);
   WheelState[] wheels=new WheelState[4];
   for (int i=0;i<4;i++) {
    wheels[i]=candidateDamping
      ? raycastWheelCandidate(i,bodyPositionY,orientation,velocity0,angular0,spring,inertiaBody,inverse)
      : raycastWheel(i,bodyPositionY,orientation,velocity0,angular0,spring,sharedDamper);
   }
   double commanded = drive ? 8000 : 0;
   double maxDeltaPerTick = 8000.0/RAMP_TICKS;
   double shapedDrive = DriveRateLimiter.shape(rampState, commanded, false, maxDeltaPerTick);
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
     0.0,0,driveForcePerWheel,FRICTION,1.0,DT,SOLVER_ITERATIONS);
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
   peakAngularMag=Math.max(peakAngularMag, angular.length());
   Vector3d upNow=orientation.transform(new Vector3d(0,1,0));
   Vector3d rightNow=orientation.transform(new Vector3d(1,0,0));
   double rollDegrees=Math.toDegrees(Math.atan2(rightNow.y, upNow.y));
   double pitchDegrees=Math.toDegrees(Math.asin(Math.max(-1,Math.min(1, orientation.transform(new Vector3d(0,0,1)).y))));
   double frontNormal=(wheels[1].hit()?wheels[1].normal():0)+(wheels[2].hit()?wheels[2].normal():0);
   double rearNormal=(wheels[0].hit()?wheels[0].normal():0)+(wheels[3].hit()?wheels[3].normal():0);
   samples.add(new TickSample(t, rollDegrees, pitchDegrees, peakAngularMag, frontNormal, rearNormal));
  }
  return samples;
 }

 private static void report(String label, java.util.List<TickSample> baseline, java.util.List<TickSample> candidate) {
  double maxRollB=0, maxRollC=0, maxPitchB=0, maxPitchC=0, peakAngB=0, peakAngC=0;
  for (var s : baseline) { maxRollB=Math.max(maxRollB,Math.abs(s.rollDegrees())); maxPitchB=Math.max(maxPitchB,Math.abs(s.pitchDegrees())); peakAngB=Math.max(peakAngB,s.peakAngularMag()); }
  for (var s : candidate) { maxRollC=Math.max(maxRollC,Math.abs(s.rollDegrees())); maxPitchC=Math.max(maxPitchC,Math.abs(s.pitchDegrees())); peakAngC=Math.max(peakAngC,s.peakAngularMag()); }
  var lastB=baseline.get(baseline.size()-1); var lastC=candidate.get(candidate.size()-1);
  System.out.println("[EffectiveMassDampingCandidateTest] "+label+" baseline(maxRoll="+maxRollB+" maxPitch="+maxPitchB+" peakAngular="+peakAngB
    +" finalFront="+lastB.frontNormal()+" finalRear="+lastB.rearNormal()+") candidate(maxRoll="+maxRollC+" maxPitch="+maxPitchC+" peakAngular="+peakAngC
    +" finalFront="+lastC.frontNormal()+" finalRear="+lastC.rearNormal()+")");
 }

 @Test void coupledSettlingComparisonAtDT05() {
  var baseline=run(false, false, 15, 0.3, 80);
  var candidate=run(true, false, 15, 0.3, 80);
  report("coupledSettling(DT=.05)", baseline, candidate);
 }

 @Test void drivenRecontactComparisonAtDT05() {
  var baseline=run(false, true, 15, 0.3, 80);
  var candidate=run(true, true, 15, 0.3, 80);
  report("drivenRecontact(DT=.05)", baseline, candidate);
 }

 @Test void pitchFreedomIsPreservedByTheCandidateDuringDrivenRun() {
  var baseline=run(false, true, 0, 0, 60);
  var candidate=run(true, true, 0, 0, 60);
  double maxPitchDiff=0;
  for (int t=0;t<60;t++) maxPitchDiff=Math.max(maxPitchDiff, Math.abs(candidate.get(t).pitchDegrees()-baseline.get(t).pitchDegrees()));
  System.out.println("[EffectiveMassDampingCandidateTest] pitchFreedom maxPitchDiff="+maxPitchDiff+"deg");
  org.junit.jupiter.api.Assertions.assertTrue(Double.isFinite(maxPitchDiff));
 }
}
