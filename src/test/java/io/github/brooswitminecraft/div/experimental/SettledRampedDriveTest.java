package io.github.brooswitminecraft.div.experimental;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The settled-initial-state, bounded-throttle-ramp drive analysis requested in ticket comments
 * 53072/53076/53085 - a more realistic counterfactual than {@link DriveOnOffPitchCounterfactualTest}
 * (instant full throttle from a cold, non-equilibrium stop). Uses the real, UNCHANGED chassis
 * geometry - no recentering - and the corrected {@link MultiContactFrictionSolver}. No arbitrary
 * threshold or anti-roll clamp is added; this only changes the TEST's own starting conditions,
 * not any production code or tuning.
 *
 * <p>"Settled": starts at the suspension's own static equilibrium compression
 * (SUSPENSION_TRAVEL/2=0.15 for all 4 wheels, derived the same way as the equilibrium CG height -
 * comment 53072/53085), zero velocity, zero angular velocity - not the transient tick0 value
 * (0.05 compression) used elsewhere in this investigation's earlier harnesses.
 *
 * <p>"Bounded throttle ramp": drive force increases linearly from 0 to the real configured max
 * (8000N) over {@code RAMP_TICKS} ticks, rather than snapping instantly to full throttle.
 */
class SettledRampedDriveTest {
 private static final double MASS=350.0, GRAVITY=11.0, DT=0.05, FRICTION=1.0, SUSPENSION_TRAVEL=0.3, WHEEL_RADIUS=0.375, LENGTH=WHEEL_RADIUS+SUSPENSION_TRAVEL, MAX_DRIVE=8000.0, BRAKE_FORCE=12000.0;
 private static final double EQUILIBRIUM_BODY_POSITION_Y=0.803593748062849; // compression=0.15 for all wheels at identity orientation
 private static final int RAMP_TICKS=40; // 2 seconds at 20tps to reach full throttle - a bounded, gradual ramp, not instant
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
 private record TickSample(int tick, double tiltDegreesFromUp, double frontNormal, double rearNormal, double commandedForce, double realizedForce) {}

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

 @Test void rampedThrottleFromSettledRestStillLosesFrontSupportButLaterThanInstantThrottle() {
  var ramped=run(true, 300);
  var instant=run(false, 300);
  TickSample rampedFirstLoss=ramped.stream().filter(s->s.frontNormal()==0).findFirst().orElse(null);
  TickSample instantFirstLoss=instant.stream().filter(s->s.frontNormal()==0).findFirst().orElse(null);
  assertNotNull(instantFirstLoss, "instant-throttle run should still lose front support within the sampled window");
  System.out.println("[SettledRampedDriveTest] instant-throttle first front-support-loss: "+instantFirstLoss);
  System.out.println("[SettledRampedDriveTest] ramped-throttle first front-support-loss: "+rampedFirstLoss);
  for (int t=0; t<=Math.min((rampedFirstLoss!=null?rampedFirstLoss.tick():299)+5, 299); t+=10) {
   TickSample r=ramped.get(t);
   System.out.println("  t="+t+" ramped(tilt="+r.tiltDegreesFromUp()+"deg front="+r.frontNormal()+" rear="+r.rearNormal()
     +" commanded="+r.commandedForce()+" realized="+r.realizedForce()+")");
  }
  if (rampedFirstLoss!=null) assertTrue(rampedFirstLoss.tick() > instantFirstLoss.tick(),
    "a bounded throttle ramp from a settled rest should delay front-support-loss onset compared to instant full throttle from a cold stop - instant lost at t="+instantFirstLoss.tick()+", ramped lost at t="+rampedFirstLoss.tick());
  // Realized force must never exceed commanded/traction-limited target, checked up to first loss.
  int checkUntil = rampedFirstLoss!=null ? rampedFirstLoss.tick() : ramped.size()-1;
  for (TickSample s : ramped) {
   if (s.tick() > checkUntil) break;
   assertTrue(Math.abs(s.realizedForce()) <= Math.abs(s.commandedForce())+1e-6,
     "realized longitudinal FORCE must never exceed the commanded/traction-limited target at t="+s.tick()+" - commanded="+s.commandedForce()+" realized="+s.realizedForce());
  }
 }

 private static java.util.List<TickSample> run(boolean ramped, int ticks) {
  Vector3d inertiaBody=inertiaBody();
  Vector3d velocity=new Vector3d(0,0,0);
  Vector3d angular=new Vector3d(0,0,0); // settled: zero velocity/angular
  Quaterniond orientation=new Quaterniond();
  double bodyPositionY=EQUILIBRIUM_BODY_POSITION_Y; // settled: equilibrium compression, not the transient tick0 value
  double spring=suspensionSpring(4), damper=suspensionDamper(spring,4);
  var samples=new java.util.ArrayList<TickSample>();
  for (int t=0;t<ticks;t++) {
   double drive = ramped ? MAX_DRIVE*Math.min(1.0,(t+1)/(double)RAMP_TICKS) : MAX_DRIVE;
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
     false,BRAKE_FORCE/4.0,driveForcePerWheel,FRICTION,DT,MultiContactFrictionSolver.FRICTION_SOLVER_ITERATIONS);
   velocity.add(new Vector3d(solverVelocity).sub(bf));
   angular.add(new Vector3d(solverAngular).sub(ba));
   double realizedForce=0; for (var r : results) realizedForce+=r.longitudinal();
   double frontNormal=0, rearNormal=0;
   for (WheelState w : wheels) { if (!w.hit()) continue; if (w.front()) frontNormal+=w.normal(); else rearNormal+=w.normal(); }
   bodyPositionY += velocity.y*DT;
   orientation.integrate(DT, angular.x, angular.y, angular.z);
   Vector3d upNow=orientation.transform(new Vector3d(0,1,0));
   double tiltDegreesFromUp=Math.toDegrees(Math.acos(Math.max(-1,Math.min(1,upNow.y))));
   samples.add(new TickSample(t, tiltDegreesFromUp, frontNormal, rearNormal, drive, realizedForce));
  }
  return samples;
 }
}
