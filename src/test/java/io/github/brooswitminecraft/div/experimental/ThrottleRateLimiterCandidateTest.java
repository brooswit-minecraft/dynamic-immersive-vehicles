package io.github.brooswitminecraft.div.experimental;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Evaluates the candidate {@link DriveRateLimiter} against the real captured instant-throttle
 * onset (ticket comment 53113: settled, nearly level upright baseline through tick58; at79 drive
 * commanded 0-&gt;8000N, realized 3867 then 4075N; at80 all4 rays still hit but one normal=0; first
 * ray miss at82; forward elevation -2.1deg{@literal @}79 -&gt; 25.2deg{@literal @}87 -&gt; 29.1deg{@literal @}88,
 * all BEFORE the first steering input at114 - i.e. a pure drive-induced wheelie, not a
 * steering/terrain effect). Re-based here to t=0 at the real tick79 commandand, starting from the
 * same settled equilibrium as {@link SettledRampedDriveTest}.
 *
 * <p>Diagnostic/test-only - {@link DriveRateLimiter} is NOT wired into {@code PhysicsBridge}.
 * Reuses the same raycast/suspension/solver harness as {@code SettledRampedDriveTest}.
 *
 * <p>Per comment 53096/53113's explicit requirement: no single candidate rate is asserted as
 * "the" right one here - this reports front-support-loss tick and forward-elevation at the two
 * real checkpoints (t=8 real~87, t=9 real~88) for several candidate full-ramp durations, so a
 * default can be chosen by comparing against the real numbers above rather than by picking an
 * arbitrary constant. It also makes explicit, per 53096, that NONE of these candidates can
 * prevent eventual support loss under commanded force SUSTAINED at the full 8000N - max drive
 * force already exceeds this ATV's own static wheelie threshold (~3300-3760N depending on
 * correction, computed in {@code WheelieStaticThresholdTest}), so a rate limiter can only reduce/
 * delay the DYNAMIC overshoot on top of that already-exceeded static margin, not remove it.
 */
class ThrottleRateLimiterCandidateTest {
 private static final double MASS=350.0, GRAVITY=11.0, DT=0.05, FRICTION=1.0, SUSPENSION_TRAVEL=0.3, WHEEL_RADIUS=0.375, LENGTH=WHEEL_RADIUS+SUSPENSION_TRAVEL, MAX_DRIVE=8000.0, BRAKE_FORCE=12000.0;
 private static final double EQUILIBRIUM_BODY_POSITION_Y=0.803593748062849;
 private static final Vector3d HALF_EXTENTS=new Vector3d(0.42500001192092896,0.4410937510430813,1.050000011920929);
 private static final Vector3d CENTER=new Vector3d(0,0.553593748062849,0.625);
 private static final Vector3d[] LOCAL_OFFSETS={
  new Vector3d(0.35,0.125,-0.0625), new Vector3d(-0.375,0.125,1.25),
  new Vector3d(0.375,0.125,1.25), new Vector3d(-0.375,0.125,-0.0625)};
 private static final boolean[] IS_FRONT={false,true,true,false};
 private static Vector3d anchorLocal(int i) { return new Vector3d(LOCAL_OFFSETS[i].x, LOCAL_OFFSETS[i].y+SUSPENSION_TRAVEL*.5, LOCAL_OFFSETS[i].z).sub(CENTER); }
 private static Vector3d inertiaBody() {
  double a=HALF_EXTENTS.x,b=HALF_EXTENTS.y,c=HALF_EXTENTS.z;
  return new Vector3d(MASS/3*(b*b+c*c), MASS/3*(a*a+c*c), MASS/3*(a*a+b*b));
 }
 private static double suspensionSpring(int n) { return MASS*GRAVITY/(n*SUSPENSION_TRAVEL*.5); }
 private static double suspensionDamper(double spring, int n) { return 2*Math.sqrt(spring*MASS/n)*.7; }
 private static double suspensionForce(double c, double v, double spring, double damper) { return Math.max(0, c*spring-v*damper); }
 private record WheelState(Vector3d anchorWorld, Vector3d up, boolean hit, double normal, Vector3d point, Vector3d forward, Vector3d right, boolean front) {}
 private record TickSample(int tick, double forwardElevationDegrees, double frontNormal, double rearNormal, double commandedForce, double realizedForce) {}

 private static WheelState raycastWheel(int i, double bodyPositionY, Quaterniond orientation, Vector3d velocity, Vector3d angular, double spring, double damper) {
  Vector3d anchorWorld=orientation.transform(new Vector3d(anchorLocal(i)));
  Vector3d up=orientation.transform(new Vector3d(0,1,0));
  Vector3d forward=orientation.transform(new Vector3d(0,0,1));
  Vector3d right=new Vector3d(up).cross(forward);
  double startY=bodyPositionY+anchorWorld.y;
  double tt=startY/(LENGTH*up.y);
  if (!(tt>=0&&tt<=1)||!Double.isFinite(tt)) return new WheelState(anchorWorld,up,false,0,null,forward,right,IS_FRONT[i]);
  double compression=LENGTH*(1-tt);
  Vector3d point=new Vector3d(anchorWorld).fma(-tt*LENGTH,up);
  Vector3d localVelocity=new Vector3d(angular).cross(anchorWorld).add(velocity);
  double normal=Math.min(MASS*GRAVITY, suspensionForce(compression, localVelocity.dot(up), spring, damper));
  return new WheelState(anchorWorld,up,true,normal,point,forward,right,IS_FRONT[i]);
 }

 @Test void candidateRatesDelayOnsetRelativeToInstantAndNoneEliminateEventualSupportLoss() {
  int[] fullRampTicks = {10,20,40,80};
  var instant = run(Double.POSITIVE_INFINITY, 120);
  TickSample instantLoss = instant.stream().filter(s->s.frontNormal()==0).findFirst().orElse(null);
  assertNotNull(instantLoss, "unlimited instant throttle must still reproduce support loss, matching the real capture's rapid onset");
  System.out.println("[ThrottleRateLimiterCandidateTest] real capture: normal=0 observed at real~80 (t=1 here), elevation -2.1@t=0, 25.2@t=8, 29.1@t=9 (real ticks 79/87/88)");
  System.out.println("[ThrottleRateLimiterCandidateTest] instant (no limiter): first front-support-loss t="+instantLoss.tick()+" elevation@t8="+instant.get(8).forwardElevationDegrees()+" elevation@t9="+instant.get(9).forwardElevationDegrees());
  for (int fullRamp : fullRampTicks) {
   double maxDeltaPerTick = MAX_DRIVE/fullRamp;
   var samples = run(maxDeltaPerTick, 120);
   TickSample loss = samples.stream().filter(s->s.frontNormal()==0).findFirst().orElse(null);
   System.out.println("[ThrottleRateLimiterCandidateTest] fullRamp="+fullRamp+"ticks (maxDelta="+maxDeltaPerTick+"N/tick): first front-support-loss t="
     +(loss!=null?loss.tick():"none in 120 ticks")+" elevation@t8="+samples.get(8).forwardElevationDegrees()+" elevation@t9="+samples.get(9).forwardElevationDegrees());
   if (loss != null) {
    assertTrue(loss.tick() >= instantLoss.tick(), "every rate-limited candidate must delay (never worsen) onset relative to unlimited instant throttle - fullRamp="+fullRamp);
   }
  }
  // Explicit, measured disclosure (not asserted as a single number - the point is it is NOT infinite/prevented):
  // even the slowest tested candidate (fullRamp=80) must still lose front support within this window,
  // because sustained 8000N exceeds the static wheelie threshold regardless of ramp rate.
  double slowestMaxDelta = MAX_DRIVE/80;
  var slowest = run(slowestMaxDelta, 400);
  TickSample slowestLoss = slowest.stream().filter(s->s.frontNormal()==0).findFirst().orElse(null);
  assertNotNull(slowestLoss, "even an 80-tick ramp to full throttle must eventually lose front support once commanded force is HELD at the full 8000N - confirms a rate limiter alone cannot prevent tipping under sustained full throttle, only delay/soften the transient");
  System.out.println("[ThrottleRateLimiterCandidateTest] slowest candidate (fullRamp=80) still loses front support once held at full throttle: t="+slowestLoss.tick());
 }

 private static java.util.List<TickSample> run(double maxDeltaPerTick, int ticks) {
  Vector3d inertiaBody=inertiaBody();
  Vector3d velocity=new Vector3d(0,0,0);
  Vector3d angular=new Vector3d(0,0,0);
  Quaterniond orientation=new Quaterniond();
  double bodyPositionY=EQUILIBRIUM_BODY_POSITION_Y;
  double spring=suspensionSpring(4), damper=suspensionDamper(spring,4);
  var limiterState=new DriveRateLimiter.State();
  var samples=new java.util.ArrayList<TickSample>();
  for (int t=0;t<ticks;t++) {
   double commanded = MAX_DRIVE; // the real event: throttle commanded to full starting at t=0 (real tick79)
   double drive = Double.isInfinite(maxDeltaPerTick) ? commanded : DriveRateLimiter.apply(limiterState, commanded, maxDeltaPerTick);
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
   double driveForcePerWheel=active.isEmpty()?0:drive/4.0;
   var results=MultiContactFrictionSolver.solveFriction(contacts,MASS,inertiaBody,orientation,inverse,solverVelocity,solverAngular,
     false,BRAKE_FORCE/4.0,driveForcePerWheel,FRICTION,1.0,DT,MultiContactFrictionSolver.FRICTION_SOLVER_ITERATIONS);
   velocity.add(new Vector3d(solverVelocity).sub(bf));
   angular.add(new Vector3d(solverAngular).sub(ba));
   double realizedForce=0; for (var r : results) realizedForce+=r.longitudinal();
   double frontNormal=0, rearNormal=0;
   for (WheelState w : wheels) { if (!w.hit()) continue; if (w.front()) frontNormal+=w.normal(); else rearNormal+=w.normal(); }
   bodyPositionY += velocity.y*DT;
   orientation.integrate(DT, angular.x, angular.y, angular.z);
   Vector3d forwardNow=orientation.transform(new Vector3d(0,0,1));
   double forwardElevationDegrees=Math.toDegrees(Math.asin(Math.max(-1,Math.min(1,forwardNow.y))));
   samples.add(new TickSample(t, forwardElevationDegrees, frontNormal, rearNormal, drive, realizedForce));
  }
  return samples;
 }
}
