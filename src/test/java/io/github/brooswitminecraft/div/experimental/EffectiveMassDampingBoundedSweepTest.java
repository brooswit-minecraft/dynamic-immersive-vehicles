package io.github.brooswitminecraft.div.experimental;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/**
 * Bounded deterministic paired sweep requested in comment 53217, closing out the effective-mass
 * damping candidate evaluation (29d2ed3/98bf372/90ed051) before moving on per that same comment's
 * explicit instruction ("if isolated effective-mass damping lacks a clear robust benefit, do not
 * tune until one seed wins... move to the next concrete Offroad lesson").
 *
 * <p>Addresses the specific gaps 53217 named in the prior sweep:
 * <ul>
 *  <li>Finite roll/pitch alone does not establish stability (atan2/asin bound the OUTPUT even if
 *  velocity/energy diverges) - this harness now tracks linear+angular velocity and total
 *  (translational+rotational) kinetic energy every tick and asserts both stay finite and below a
 *  generous blow-up cap, not just that the reported angle is a finite number.</li>
 *  <li>No terrain/body collision after rollover exists in this harness, so a near-180 degree
 *  trajectory past vertical is not validated real-world behavior - this harness now STOPS
 *  advancing a run once it crosses an unambiguous tip threshold (120 degrees - well past the
 *  90 degree "lost the fight against gravity" line, before the harness's un-modeled post-tip
 *  numerics become meaningless) and classifies tipped vs recovered by that tick, rather than
 *  reading a final angle from a run that kept integrating a physically-unmodeled tumble.</li>
 *  <li>Two initial headings (yaw=0, yaw=90) paired with each (roll seed, angular seed) combo, so
 *  the result isn't an artifact of one heading's wheel-asymmetry interaction.</li>
 *  <li>Brake scenario now starts from a nonzero forward speed with an explicit drive-then-brake
 *  schedule (accelerate for 20 ticks, then brake) - not braking at rest, which never exercises
 *  forward-weight-transfer-under-braking at all.</li>
 * </ul>
 *
 * <p>Labels "chaotic/bistable near a threshold" as what this sweep's own counts show (near-even
 * split, sign flips between adjacent seeds), not as an a-priori assumption - the bounded
 * energy/velocity check is what rules out "it's actually a harness divergence bug" as the
 * alternative explanation.
 */
class EffectiveMassDampingBoundedSweepTest {
 private static final double MASS=350.0, GRAVITY=11.0, FRICTION=1.0, SUSPENSION_TRAVEL=0.3, WHEEL_RADIUS=0.375, LENGTH=WHEEL_RADIUS+SUSPENSION_TRAVEL;
 private static final double EQUILIBRIUM_BODY_POSITION_Y=0.803593748062849;
 private static final Vector3d CENTER=new Vector3d(0,0.553593748062849,0.625);
 private static final Vector3d[] REAL_OFFSETS={
  new Vector3d(0.35,0.125,-0.0625), new Vector3d(-0.375,0.125,1.25),
  new Vector3d(0.375,0.125,1.25), new Vector3d(-0.375,0.125,-0.0625)};
 private static final int SOLVER_ITERATIONS=64;
 private static final SelfRightingAssist.Config QUAD_EXAMPLE=new SelfRightingAssist.Config(200,80,600,10,50);
 private static final double BRAKE_FORCE=12000.0;
 private static final double TIP_THRESHOLD_DEGREES=120.0; // past this, no ground/body collision is modeled - stop, don't trust further integration
 private static final double ENERGY_BLOWUP_CAP_JOULES=1.0e7; // generous - real peak KE here is O(1e3-1e4)J; this only catches genuine divergence

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

 private record Outcome(boolean tipped, int tickOfTip, double maxRollDegrees, double finalKineticEnergyJoules, boolean energyStayedBounded) {}

 /** @param initialYawDegrees the paired-heading axis 53217 asked for. @param driveThenBrake when
  *  true, runs a real drive-then-brake schedule (accelerate 20 ticks, brake thereafter) instead of
  *  a constant drive - used by the fixed brake scenario so braking happens from real forward
  *  speed, not from rest. */
 private static Outcome run(boolean candidateDamping, double dt, boolean drive, boolean driveThenBrake,
     double initialRollDegrees, double initialAngularSeedZ, double initialYawDegrees, int ticks) {
  Vector3d inertiaBody=inertiaBody();
  Vector3d velocity=new Vector3d(0,0,0), angular=new Vector3d(0,0,initialAngularSeedZ);
  Quaterniond orientation=new Quaterniond().rotateZ(Math.toRadians(-initialRollDegrees)).rotateY(Math.toRadians(initialYawDegrees));
  double bodyPositionY=EQUILIBRIUM_BODY_POSITION_Y;
  double spring=suspensionSpring(4);
  double sharedDamper=currentDamper(spring,4);
  var rampState=new DriveRateLimiter.State();
  double maxDeltaPerTick=8000.0/(20*0.05)*dt; // physical 20-tick-at-DT=.05 ramp rate, scaled to this dt
  double maxRoll=0;
  boolean energyStayedBounded=true;
  for (int t=0;t<ticks;t++) {
   Quaterniond inverse=new Quaterniond(orientation).conjugate();
   Vector3d velocity0=new Vector3d(velocity), angular0=new Vector3d(angular);
   WheelState[] wheels=new WheelState[4];
   for (int i=0;i<4;i++) {
    wheels[i]=candidateDamping
      ? raycastWheelCandidate(i,bodyPositionY,orientation,velocity0,angular0,spring,inertiaBody,inverse)
      : raycastWheel(i,bodyPositionY,orientation,velocity0,angular0,spring,sharedDamper);
   }
   boolean braking = driveThenBrake && t>=20;
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
   for (int k=0;k<active.size();k++) { WheelState w=active.get(k); contacts[k]=new MultiContactFrictionSolver.FrictionContact(w.point(),w.forward(),w.right(),w.normal(),1.0); }
   Vector3d bf=new Vector3d(solverVelocity), ba=new Vector3d(solverAngular);
   double driveForcePerWheel=active.isEmpty()?0:shapedDrive/4.0;
   MultiContactFrictionSolver.solveFriction(contacts,MASS,inertiaBody,orientation,inverse,solverVelocity,solverAngular,
     braking,braking?BRAKE_FORCE/4.0:0,driveForcePerWheel,FRICTION,1.0,dt,SOLVER_ITERATIONS);
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

   Quaterniond inverseNow=new Quaterniond(orientation).conjugate();
   Vector3d angularBody=inverseNow.transform(new Vector3d(angular));
   double rotationalKE=0.5*(angularBody.x*angularBody.x*inertiaBody.x + angularBody.y*angularBody.y*inertiaBody.y + angularBody.z*angularBody.z*inertiaBody.z);
   double translationalKE=0.5*MASS*velocity.lengthSquared();
   double kineticEnergy=translationalKE+rotationalKE;
   if (!Double.isFinite(kineticEnergy) || !Double.isFinite(velocity.length()) || !Double.isFinite(angular.length()) || kineticEnergy>ENERGY_BLOWUP_CAP_JOULES) {
    energyStayedBounded=false;
   }
   Vector3d rightNow=orientation.transform(new Vector3d(1,0,0));
   Vector3d upNow=orientation.transform(new Vector3d(0,1,0));
   double rollNow=Math.abs(Math.toDegrees(Math.atan2(rightNow.y, upNow.y)));
   maxRoll=Math.max(maxRoll, rollNow);
   if (rollNow>=TIP_THRESHOLD_DEGREES) {
    // Stop advancing - past this point there is no ground/body collision modeled, so continuing
    // integration would not represent validated real-world behavior (53217's explicit correction).
    return new Outcome(true, t, maxRoll, kineticEnergy, energyStayedBounded);
   }
  }
  Quaterniond inverseFinal=new Quaterniond(orientation).conjugate();
  Vector3d angularBodyFinal=inverseFinal.transform(new Vector3d(angular));
  double rotationalKE=0.5*(angularBodyFinal.x*angularBodyFinal.x*inertiaBody.x + angularBodyFinal.y*angularBodyFinal.y*inertiaBody.y + angularBodyFinal.z*angularBodyFinal.z*inertiaBody.z);
  double translationalKE=0.5*MASS*velocity.lengthSquared();
  return new Outcome(false, ticks, maxRoll, translationalKE+rotationalKE, energyStayedBounded);
 }

 @Test void boundedPairedSweepAcrossSeedsHeadingsAndTimesteps() {
  double[] rollSeeds={5,10,15,20,25,30};
  double[] angularSeeds={0.15,0.3};
  double[] headings={0,90};
  double[] dts={0.05,0.025,0.0125};
  int tippedBaseline=0, tippedCandidate=0, candidateBetter=0, candidateWorse=0, total=0;
  boolean allEnergyBounded=true;
  for (double dt : dts) {
   int ticks=(int)Math.round(80*0.05/dt);
   for (double roll : rollSeeds) for (double ang : angularSeeds) for (double heading : headings) {
    var baseline=run(false, dt, true, false, roll, ang, heading, ticks);
    var candidate=run(true, dt, true, false, roll, ang, heading, ticks);
    total++;
    if (baseline.tipped()) tippedBaseline++;
    if (candidate.tipped()) tippedCandidate++;
    if (!baseline.energyStayedBounded() || !candidate.energyStayedBounded()) {
     allEnergyBounded=false;
     System.out.println("[EffectiveMassDampingBoundedSweepTest] ENERGY BLOWUP dt="+dt+" roll="+roll+" ang="+ang+" heading="+heading
       +" baselineBounded="+baseline.energyStayedBounded()+" candidateBounded="+candidate.energyStayedBounded());
    }
    int tipCompare = (candidate.tipped()?1:0) - (baseline.tipped()?1:0);
    if (tipCompare<0) candidateBetter++; else if (tipCompare>0) candidateWorse++;
   }
  }
  System.out.println("[EffectiveMassDampingBoundedSweepTest] total="+total+" tippedBaseline="+tippedBaseline+" tippedCandidate="+tippedCandidate
    +" candidateBetter="+candidateBetter+" candidateWorse="+candidateWorse+" allEnergyBounded="+allEnergyBounded);
  org.junit.jupiter.api.Assertions.assertTrue(allEnergyBounded,
    "every run (baseline and candidate, across all seeds/headings/timesteps) must show finite, bounded kinetic energy - this is the check that rules out harness divergence as the explanation for any near-tip trajectory, per comment 53217");
 }

 /** Fixed per 53217's concrete harness gap: braking now happens from real forward speed after a
  * drive phase, not from rest (velocity=(0,0,0) with braking true throughout tests nothing about
  * forward-weight-transfer-under-braking). */
 @Test void driveThenBrakeFromRealForwardSpeedDoesNotRegress() {
  var baseline=run(false, 0.05, true, true, 15, 0.3, 0, 80);
  var candidate=run(true, 0.05, true, true, 15, 0.3, 0, 80);
  System.out.println("[EffectiveMassDampingBoundedSweepTest] driveThenBrake baseline(tipped="+baseline.tipped()+" maxRoll="+baseline.maxRollDegrees()
    +" energyBounded="+baseline.energyStayedBounded()+") candidate(tipped="+candidate.tipped()+" maxRoll="+candidate.maxRollDegrees()
    +" energyBounded="+candidate.energyStayedBounded()+")");
  org.junit.jupiter.api.Assertions.assertTrue(baseline.energyStayedBounded() && candidate.energyStayedBounded(),
    "drive-then-brake from real forward speed must not diverge for either baseline or candidate");
 }
}
