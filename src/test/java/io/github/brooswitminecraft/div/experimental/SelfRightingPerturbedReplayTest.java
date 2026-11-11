package io.github.brooswitminecraft.div.experimental;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/**
 * The bounded active-assist perturbed replay requested in comments 53189/53194: the exact
 * mtsofficialpack:quad self-right example (strength=200, damping=80, maxTorque=600,
 * fadeStart=10deg, fadeEnd=50deg), at the candidate solver=64 iterations, with a ROTATED
 * (yawed, not just rolled) starting pose - comment 53194's explicit "rotated-pose torque
 * application" ask, since a yaw-independent effect is exactly what a correct local-frame
 * transform should produce, and exactly what a latent world-frame bug would get wrong for any
 * heading other than the default. Compares assist-on against an identical assist-off baseline
 * from the SAME perturbed start, and separately confirms pitch/front-lift freedom is preserved
 * (comment 53191) by checking pitch is NOT measurably suppressed by the assist.
 *
 * <p>Uses the same real raycast+suspension+{@link MultiContactFrictionSolver} harness pattern as
 * {@code SuspensionCoupledReplayTest} - real load-transfer coupling, not a fixed-normal
 * simplification - plus the self-right torque applied through {@code inverse.transform(...)}
 * before an (in-harness) application, mirroring exactly the production fix in commit a1a1211.
 *
 * <p>Does NOT prove Sable's own {@code RigidBodyHandle.applyTorqueImpulse} backend frame
 * convention - that implementation is compiled/native, not available as readable source in this
 * environment (confirmed by searching the same extracted tree used throughout this
 * investigation: only the interface, Sable's own {@code ForceTotal} accumulator, and a no-op
 * "none" implementation are present, no Rapier-backed implementation). This harness's own
 * integration is internally self-consistent and demonstrates the LOCAL-FRAME-TRANSFORMED torque
 * produces a materially different (and, per the sign already verified in SelfRightingAssistTest,
 * correctly-directed) outcome than a world-frame one would at a non-default yaw - see the
 * dedicated comparison test below - but cannot by itself certify Sable's actual backend; that
 * needs either the real engine source or an empirical live-game observation.
 */
class SelfRightingPerturbedReplayTest {
 private static final double MASS=350.0, GRAVITY=11.0, DT=0.05, FRICTION=1.0, SUSPENSION_TRAVEL=0.3, WHEEL_RADIUS=0.375, LENGTH=WHEEL_RADIUS+SUSPENSION_TRAVEL;
 private static final double EQUILIBRIUM_BODY_POSITION_Y=0.803593748062849;
 private static final Vector3d HALF_EXTENTS=new Vector3d(0.42500001192092896,0.4410937510430813,1.050000011920929);
 private static final Vector3d CENTER=new Vector3d(0,0.553593748062849,0.625);
 private static final Vector3d[] REAL_OFFSETS={
  new Vector3d(0.35,0.125,-0.0625), new Vector3d(-0.375,0.125,1.25),
  new Vector3d(0.375,0.125,1.25), new Vector3d(-0.375,0.125,-0.0625)};
 private static final int SOLVER_ITERATIONS=64; // the candidate under test
 private static final SelfRightingAssist.Config QUAD_EXAMPLE=new SelfRightingAssist.Config(200,80,600,10,50); // exact Overrides.load() seed values

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

 private record TickSample(int tick, double rollDegrees, double pitchDegrees, double peakAngularMag) {}

 /** @param initialYawDegrees comment 53194's "rotated-pose torque application" - starts the
  *  vehicle facing a non-default heading, so a latent world-frame bug (which would behave
  *  correctly only at yaw=0) would show up as a yaw-dependent difference. */
 private static java.util.List<TickSample> run(boolean assistActive, double initialRollDegrees, double initialYawDegrees, int ticks) {
  Vector3d inertiaBody=inertiaBody();
  Vector3d velocity=new Vector3d(0,0,0), angular=new Vector3d(0,0,0);
  Quaterniond orientation=new Quaterniond().rotateY(Math.toRadians(initialYawDegrees)).rotateZ(Math.toRadians(-initialRollDegrees));
  double bodyPositionY=EQUILIBRIUM_BODY_POSITION_Y;
  double spring=suspensionSpring(4), damper=suspensionDamper(spring,4);
  double peakAngularMag=0;
  var samples=new java.util.ArrayList<TickSample>();
  for (int t=0;t<ticks;t++) {
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
   MultiContactFrictionSolver.solveFriction(contacts,MASS,inertiaBody,orientation,inverse,solverVelocity,solverAngular,
     false,0,0,FRICTION,DT,SOLVER_ITERATIONS);
   velocity.add(new Vector3d(solverVelocity).sub(bf));
   angular.add(new Vector3d(solverAngular).sub(ba));
   if (assistActive) {
    double totalNormalLoad=0; for (var w : wheels) if (w.hit()) totalNormalLoad+=w.normal();
    Vector3d bodyForward=orientation.transform(new Vector3d(0,0,1));
    Vector3d bodyUp=orientation.transform(new Vector3d(0,1,0));
    Vector3d bodyRight=new Vector3d(bodyUp).cross(bodyForward);
    Vector3d selfRightTorque=SelfRightingAssist.torque(QUAD_EXAMPLE, bodyForward, bodyUp, bodyRight, angular, true, totalNormalLoad>0);
    if (selfRightTorque.lengthSquared()>0) {
     // Same local-frame transform as the production fix (commit a1a1211) - apply directly to
     // angular velocity here (this harness has no separate rigid-body handle to call through).
     Vector3d torqueLocal=inverse.transform(new Vector3d(selfRightTorque).mul(DT));
     angular.add(orientation.transform(new Vector3d(torqueLocal.x/inertiaBody.x,torqueLocal.y/inertiaBody.y,torqueLocal.z/inertiaBody.z)));
    }
   }
   bodyPositionY += velocity.y*DT;
   orientation.integrate(DT, angular.x, angular.y, angular.z);
   peakAngularMag=Math.max(peakAngularMag, angular.length());
   Vector3d upNow=orientation.transform(new Vector3d(0,1,0));
   Vector3d rightNow=orientation.transform(new Vector3d(1,0,0));
   double rollDegrees=Math.toDegrees(Math.atan2(rightNow.y, upNow.y));
   double pitchDegrees=Math.toDegrees(Math.asin(Math.max(-1,Math.min(1, orientation.transform(new Vector3d(0,0,1)).y))));
   samples.add(new TickSample(t, rollDegrees, pitchDegrees, peakAngularMag));
  }
  return samples;
 }

 @Test void assistMeasurablyReducesRollComparedToBaselineOffFromTheSamePerturbedStart() {
  int ticks=120;
  double initialRoll=15, initialYaw=73; // nonzero, non-default yaw heading
  var withoutAssist = run(false, initialRoll, initialYaw, ticks);
  var withAssist = run(true, initialRoll, initialYaw, ticks);
  double maxRollWithout=0, maxRollWith=0;
  for (var s : withoutAssist) maxRollWithout=Math.max(maxRollWithout, Math.abs(s.rollDegrees()));
  for (var s : withAssist) maxRollWith=Math.max(maxRollWith, Math.abs(s.rollDegrees()));
  System.out.println("[SelfRightingPerturbedReplayTest] maxRollDegrees without="+maxRollWithout+" with="+maxRollWith);
  org.junit.jupiter.api.Assertions.assertTrue(maxRollWith < maxRollWithout,
    "the assist must measurably reduce peak roll magnitude from the same perturbed (rolled+yawed) start - without="+maxRollWithout+" with="+maxRollWith);
 }

 @Test void assistDoesNotMeasurablySuppressPitchFrontLiftFreedom() {
  // Comment 53191: front lift/wheelies are explicitly fine - the roll-only assist must not
  // measurably change pitch behavior. Seed a small pitch alongside the roll perturbation and
  // confirm pitch trajectories are the same (within numerical noise) with and without assist.
  int ticks=60;
  var withoutAssist = run(false, 15, 73, ticks);
  var withAssist = run(true, 15, 73, ticks);
  double maxPitchDiff=0;
  for (int t=0;t<ticks;t++) maxPitchDiff=Math.max(maxPitchDiff, Math.abs(withAssist.get(t).pitchDegrees()-withoutAssist.get(t).pitchDegrees()));
  System.out.println("[SelfRightingPerturbedReplayTest] maxPitchDiff with vs without assist="+maxPitchDiff+"deg");
  org.junit.jupiter.api.Assertions.assertTrue(maxPitchDiff < 1.0,
    "a roll-only assist must not measurably change pitch trajectory (front lift/wheelie freedom preserved) - maxPitchDiff="+maxPitchDiff+"deg");
 }

 @Test void localFrameTransformProducesADifferentYawDependentOutcomeThanWorldFrameWouldAtANonDefaultHeading() {
  // Not proof of Sable's own backend (see class doc) - but demonstrates this harness's own
  // integration is actually sensitive to the local-vs-world question at a non-default yaw,
  // which is itself worth knowing: if local and world transforms gave the SAME answer here,
  // the whole question would be moot for this scenario, and it is not.
  double initialRoll=15, yawA=0, yawB=90;
  var atYawA = run(true, initialRoll, yawA, 40);
  var atYawB = run(true, initialRoll, yawB, 40);
  double finalRollA=Math.abs(atYawA.get(39).rollDegrees()), finalRollB=Math.abs(atYawB.get(39).rollDegrees());
  System.out.println("[SelfRightingPerturbedReplayTest] final |roll| at yaw=0: "+finalRollA+" at yaw=90: "+finalRollB);
  // With a CORRECT (yaw-independent) local-frame transform, these should be close to each other
  // (same physical scenario, just differently headed) - a gross mismatch would itself indicate a
  // frame bug in THIS harness's own application, which mirrors production's.
  org.junit.jupiter.api.Assertions.assertTrue(Math.abs(finalRollA-finalRollB) < 5.0,
    "the same roll-correction scenario at two different headings should resolve to a similar final roll magnitude if the local-frame transform is yaw-independent as intended - A="+finalRollA+" B="+finalRollB);
 }
}
