package io.github.brooswitminecraft.div.experimental;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The dynamic drive-on/off counterfactual requested in ticket comment 53069/53072, on the
 * UNCHANGED real chassis geometry (no recentering) - tracks REALIZED (not just commanded)
 * longitudinal impulse and front/rear support unloading over time, comparing a full-throttle run
 * against a zero-drive diagnostic baseline, aiming at the real captured pitch onset pattern
 * (window5, ticket comment 53064: forward elevation approx 6/22/45 degrees at ticks 120/125/131).
 *
 * <p>Uses the already-validated {@link MultiContactFrictionSolver} with the real chassis geometry/
 * mass/inertia/wheel layout (same constants as {@link SuspensionCoupledReplayTest}) - center is
 * the REAL chassis center throughout, never recentered. Front wheels are the two at local z=1.25;
 * rear wheels are the two at local z=-0.0625 (matching the real captured localOffset values).
 *
 * <p>This is a free-running counterfactual simulation, not a replay of the real capture's exact
 * values - it starts from rest (not the real capture's own mid-session velocity/orientation state
 * at the point drive was applied) and compares relative behavior (drive on vs off) rather than
 * claiming to reproduce the real capture tick-for-tick.
 *
 * <p><b>Corrected after review (comment 53076):</b> the per-wheel solver outputs
 * ({@code tire.longitudinal()}) are FORCES (newtons), not impulses - an earlier version of this
 * test mislabeled them; fixed throughout. Tilt is now measured as the angle between the current
 * and initial up-vectors ({@code acos(up.dot(initialUp))}), not {@code asin(forward.y)} - asin's
 * range is only [-90,90] degrees, so it cannot distinguish tilt past 90 degrees (a real tilt of
 * 100 degrees and one of 80 degrees can report similarly), which made an earlier version's
 * "oscillating pitch" past the first 90 degrees at least partly a measurement artifact, not
 * necessarily real dynamics. The harness also has no chassis-collision/ground-recovery model, so
 * behavior well after the vehicle goes airborne is not meaningful production evidence - the
 * raycast-against-a-flat-plane model implicitly assumes a mostly-upright body. For both reasons,
 * this test now reports and asserts on the FIRST support-loss and FIRST-45-degree-tilt ticks
 * specifically, not a late post-airborne sample.
 *
 * <p><b>Result:</b> full-throttle (8000N) from rest unloads the front axle almost immediately and
 * reaches a 45-degree tilt (by the up-vector measure) within the first several ticks - see the
 * printed onset ticks in the test output for the exact current values. The undriven baseline
 * stays essentially level over the same window. Realized longitudinal force correctly drops to 0
 * once normal force (and therefore the friction budget) reaches 0 - the friction model is NOT
 * requesting force it has no traction to deliver once airborne.
 *
 * <p>This onset is qualitatively consistent with a real wheelie/flip mechanism (drive force
 * unloads the front, tilt grows) but its onset timing is much faster than the real captured
 * window5 (tilt only 6/22/45 degrees at ticks 120/125/131). A cold-start-vs-real-control-history
 * difference (this counterfactual applies full throttle instantly from a dead stop at tick 0,
 * while the real capture's own throttle/brake activity began around tick 69, comment 53064) is a
 * PLAUSIBLE contributor to that timing gap - NOT established as the cause. The two runs are not
 * on a comparable timeline, so onset tick numbers should not be compared as if they were.
 */
class DriveOnOffPitchCounterfactualTest {
 private static final double MASS=350.0, GRAVITY=11.0, DT=0.05, FRICTION=1.0, SUSPENSION_TRAVEL=0.3, WHEEL_RADIUS=0.375, BRAKE_FORCE=12000.0;
 private static final Vector3d HALF_EXTENTS=new Vector3d(0.42500001192092896,0.4410937510430813,1.050000011920929);
 private static final Vector3d CENTER=new Vector3d(0,0.553593748062849,0.625); // REAL chassisReceipt center - unchanged
 private static final Vector3d[] LOCAL_OFFSETS={
  new Vector3d(0.35,0.125,-0.0625), new Vector3d(-0.375,0.125,1.25),
  new Vector3d(0.375,0.125,1.25), new Vector3d(-0.375,0.125,-0.0625)};
 private static final boolean[] IS_FRONT={false,true,true,false}; // z=1.25 -> front; z=-0.0625 -> rear
 private static Vector3d anchorLocal(int i) { return new Vector3d(LOCAL_OFFSETS[i].x, LOCAL_OFFSETS[i].y+SUSPENSION_TRAVEL*.5, LOCAL_OFFSETS[i].z).sub(CENTER); }
 private static Vector3d inertiaBody() {
  double a=HALF_EXTENTS.x,b=HALF_EXTENTS.y,c=HALF_EXTENTS.z;
  return new Vector3d(MASS/3*(b*b+c*c), MASS/3*(a*a+c*c), MASS/3*(a*a+b*b));
 }
 private static double suspensionSpring(int n) { return MASS*GRAVITY/(n*SUSPENSION_TRAVEL*.5); }
 private static double suspensionDamper(double spring, int n) { return 2*Math.sqrt(spring*MASS/n)*.7; }
 private static double suspensionForce(double c, double v, double spring, double damper) { return Math.max(0, c*spring-v*damper); }
 private record WheelState(Vector3d anchorWorld, Vector3d up, boolean hit, double normal, Vector3d point, Vector3d forward, Vector3d right, boolean front) {}
 private record TickSample(int tick, double tiltDegreesFromUp, double frontNormal, double rearNormal, double commandedLongitudinalForce, double realizedLongitudinalForce) {}

 @Test void drivenRunLosesFrontSupportAndTiltsFasterThanUndriven() {
  var driven=run(8000.0, 160);
  var undriven=run(0.0, 160);
  TickSample firstSupportLoss=driven.stream().filter(d->d.frontNormal()==0).findFirst().orElse(null);
  TickSample first45=driven.stream().filter(d->d.tiltDegreesFromUp()>=45).findFirst().orElse(null);
  assertNotNull(firstSupportLoss, "driven run should lose front support within the sampled window");
  assertNotNull(first45, "driven run should reach 45 degrees of tilt within the sampled window");
  System.out.println("[DriveOnOffPitchCounterfactualTest] driven first front-support-loss: "+firstSupportLoss);
  System.out.println("[DriveOnOffPitchCounterfactualTest] driven first 45-degree tilt: "+first45);
  for (int t=0; t<=Math.min(first45.tick()+5, 159); t+=5) {
   TickSample d=driven.get(t), u=undriven.get(t);
   System.out.println("  t="+t+" driven(tilt="+d.tiltDegreesFromUp()+"deg front="+d.frontNormal()+" rear="+d.rearNormal()
     +" commandedForce="+d.commandedLongitudinalForce()+" realizedForce="+d.realizedLongitudinalForce()
     +")  undriven(tilt="+u.tiltDegreesFromUp()+"deg front="+u.frontNormal()+")");
  }
  TickSample undrivenAtFirst45Tick=undriven.get(first45.tick());
  assertTrue(undrivenAtFirst45Tick.tiltDegreesFromUp() < first45.tiltDegreesFromUp(),
    "at the tick the driven run first reaches 45 degrees, the undriven baseline should show much less tilt - driven="+first45.tiltDegreesFromUp()+" undriven="+undrivenAtFirst45Tick.tiltDegreesFromUp());
  // Only check the force budget up to and including first support loss - beyond that point the
  // harness's no-ground-recovery limitation (see class doc) makes later samples unreliable.
  for (TickSample d : driven) {
   if (d.tick() > firstSupportLoss.tick()) break;
   assertTrue(Math.abs(d.realizedLongitudinalForce()) <= Math.abs(d.commandedLongitudinalForce())+1e-6,
     "realized longitudinal FORCE must never exceed the commanded/traction-limited target at t="+d.tick()+" - commanded="+d.commandedLongitudinalForce()+" realized="+d.realizedLongitudinalForce());
  }
 }

 private static java.util.List<TickSample> run(double drive, int ticks) {
  Vector3d inertiaBody=inertiaBody();
  Vector3d velocity=new Vector3d(0,0,0);
  Vector3d angular=new Vector3d(0,0,0); // start from rest - isolating drive's own effect, not a rollover seed
  Quaterniond orientation=new Quaterniond();
  double bodyPositionY=0.9036;
  double spring=suspensionSpring(4), damper=suspensionDamper(spring,4);
  var samples=new java.util.ArrayList<TickSample>();
  for (int t=0;t<ticks;t++) {
   Quaterniond inverse=new Quaterniond(orientation).conjugate();
   Vector3d velocity0=new Vector3d(velocity), angular0=new Vector3d(angular);
   WheelState[] wheels=new WheelState[4];
   for (int i=0;i<4;i++) {
    double length=WHEEL_RADIUS+SUSPENSION_TRAVEL;
    Vector3d anchorWorld=orientation.transform(new Vector3d(anchorLocal(i)));
    Vector3d up=orientation.transform(new Vector3d(0,1,0));
    Vector3d forward=orientation.transform(new Vector3d(0,0,1));
    Vector3d right=new Vector3d(up).cross(forward);
    double startY=bodyPositionY+anchorWorld.y;
    double tt=startY/(length*up.y);
    if (!(tt>=0&&tt<=1)||!Double.isFinite(tt)) { wheels[i]=new WheelState(anchorWorld,up,false,0,null,forward,right,IS_FRONT[i]); continue; }
    double compression=length*(1-tt);
    Vector3d point=new Vector3d(anchorWorld).fma(-tt*length,up);
    Vector3d localVelocity=new Vector3d(angular0).cross(anchorWorld).add(velocity0);
    double normal=Math.min(MASS*GRAVITY, suspensionForce(compression, localVelocity.dot(up), spring, damper));
    wheels[i]=new WheelState(anchorWorld,up,true,normal,point,forward,right,IS_FRONT[i]);
   }
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
   double driveForcePerWheel=active.isEmpty()?0:drive/4.0; // production divides by total configured wheel count, not active.size()
   var results=MultiContactFrictionSolver.solveFriction(contacts,MASS,inertiaBody,orientation,inverse,solverVelocity,solverAngular,
     false,BRAKE_FORCE/4.0,driveForcePerWheel,FRICTION,1.0,DT,MultiContactFrictionSolver.FRICTION_SOLVER_ITERATIONS);
   velocity.add(new Vector3d(solverVelocity).sub(bf));
   angular.add(new Vector3d(solverAngular).sub(ba));
   double realizedLongitudinalForce=0; for (var r : results) realizedLongitudinalForce+=r.longitudinal();
   double commandedLongitudinalForce=drive; // total commanded force across all 4 wheels (driveForcePerWheel*4), before traction limiting
   double frontNormal=0, rearNormal=0;
   for (WheelState w : wheels) { if (!w.hit()) continue; if (w.front()) frontNormal+=w.normal(); else rearNormal+=w.normal(); }
   bodyPositionY += velocity.y*DT;
   orientation.integrate(DT, angular.x, angular.y, angular.z);
   // acos(up.dot(initialUp)) is well-defined over the full 0-180 degree range, unlike
   // asin(forward.y) (range [-90,90]) which an earlier version used (review comment 53076).
   Vector3d upNow=orientation.transform(new Vector3d(0,1,0));
   double tiltDegreesFromUp=Math.toDegrees(Math.acos(Math.max(-1,Math.min(1,upNow.y))));
   samples.add(new TickSample(t, tiltDegreesFromUp, frontNormal, rearNormal, commandedLongitudinalForce, realizedLongitudinalForce));
  }
  return samples;
 }
}
