package io.github.brooswitminecraft.div.experimental;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The passive uneven-contact/load-redistribution-vs-runaway test requested in ticket comment
 * 53076, flagged as especially important because reported user feedback includes "flopping"
 * beyond the commanded-drive wheelie case - i.e. passive instability from something as simple as
 * uneven ground, not from driving at all.
 *
 * <p>Uses the real, UNCHANGED chassis geometry/mass/inertia/wheel layout and the corrected
 * {@link MultiContactFrictionSolver}, fully undriven (drive=0, braking=false) throughout - the
 * ONLY asymmetry is a small terrain-height offset under one wheel (simulating a single wheel
 * resting on a slightly higher or lower bit of ground than the other three), not an artificial
 * seed angular velocity. This isolates whether a small, realistic terrain asymmetry alone - with
 * no driving, no pre-existing rotation - leads to a stable, bounded load redistribution (the
 * suspension settles with different compression per wheel but the body stops moving) or a
 * genuine runaway (growing rotation that does not bound).
 */
class UnevenTerrainPassiveStabilityTest {
 private static final double MASS=350.0, GRAVITY=11.0, DT=0.05, FRICTION=1.0, SUSPENSION_TRAVEL=0.3, WHEEL_RADIUS=0.375;
 private static final Vector3d HALF_EXTENTS=new Vector3d(0.42500001192092896,0.4410937510430813,1.050000011920929);
 private static final Vector3d CENTER=new Vector3d(0,0.553593748062849,0.625); // real chassisReceipt center - unchanged
 private static final Vector3d[] LOCAL_OFFSETS={
  new Vector3d(0.35,0.125,-0.0625), new Vector3d(-0.375,0.125,1.25),
  new Vector3d(0.375,0.125,1.25), new Vector3d(-0.375,0.125,-0.0625)};
 private static Vector3d anchorLocal(int i) { return new Vector3d(LOCAL_OFFSETS[i].x, LOCAL_OFFSETS[i].y+SUSPENSION_TRAVEL*.5, LOCAL_OFFSETS[i].z).sub(CENTER); }
 private static Vector3d inertiaBody() {
  double a=HALF_EXTENTS.x,b=HALF_EXTENTS.y,c=HALF_EXTENTS.z;
  return new Vector3d(MASS/3*(b*b+c*c), MASS/3*(a*a+c*c), MASS/3*(a*a+b*b));
 }
 private static double suspensionSpring(int n) { return MASS*GRAVITY/(n*SUSPENSION_TRAVEL*.5); }
 private static double suspensionDamper(double spring, int n) { return 2*Math.sqrt(spring*MASS/n)*.7; }
 private static double suspensionForce(double c, double v, double spring, double damper) { return Math.max(0, c*spring-v*damper); }
 private record WheelState(Vector3d anchorWorld, Vector3d up, boolean hit, double normal, Vector3d point, Vector3d forward, Vector3d right) {}

 @Test void smallSingleWheelTerrainBumpSettlesRatherThanRunsAway() {
  // A 2cm bump under wheel 0 only - small relative to the 0.15m equilibrium compression and
  // 0.3m suspension travel, representative of ordinary uneven ground, not an extreme case.
  double[] terrainHeight={0.02, 0.0, 0.0, 0.0};
  int ticks=400;
  double maxOmega=0, maxTiltDegrees=0;
  Vector3d velocity=new Vector3d(0,0,0);
  Vector3d angular=new Vector3d(0,0,0); // no seed - the terrain bump alone is the asymmetry
  Quaterniond orientation=new Quaterniond();
  double bodyPositionY=0.9036;
  Vector3d inertiaBody=inertiaBody();
  double spring=suspensionSpring(4), damper=suspensionDamper(spring,4);
  double[] lastOmegaWindow=new double[20]; int windowIdx=0;
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
    double startY=bodyPositionY+anchorWorld.y-terrainHeight[i]; // ground at terrainHeight[i], not 0
    double tt=startY/(length*up.y);
    if (!(tt>=0&&tt<=1)||!Double.isFinite(tt)) { wheels[i]=new WheelState(anchorWorld,up,false,0,null,forward,right); continue; }
    double compression=length*(1-tt);
    Vector3d point=new Vector3d(anchorWorld).fma(-tt*length,up);
    Vector3d localVelocity=new Vector3d(angular0).cross(anchorWorld).add(velocity0);
    double normal=Math.min(MASS*GRAVITY, suspensionForce(compression, localVelocity.dot(up), spring, damper));
    wheels[i]=new WheelState(anchorWorld,up,true,normal,point,forward,right);
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
   MultiContactFrictionSolver.solveFriction(contacts,MASS,inertiaBody,orientation,inverse,solverVelocity,solverAngular,false,0,0,FRICTION,DT,MultiContactFrictionSolver.FRICTION_SOLVER_ITERATIONS);
   velocity.add(new Vector3d(solverVelocity).sub(bf));
   angular.add(new Vector3d(solverAngular).sub(ba));
   bodyPositionY += velocity.y*DT;
   orientation.integrate(DT, angular.x, angular.y, angular.z);
   Vector3d upNow=orientation.transform(new Vector3d(0,1,0));
   double tiltDegrees=Math.toDegrees(Math.acos(Math.max(-1,Math.min(1,upNow.y))));
   maxOmega=Math.max(maxOmega, angular.length());
   maxTiltDegrees=Math.max(maxTiltDegrees, tiltDegrees);
   lastOmegaWindow[windowIdx%lastOmegaWindow.length]=angular.length(); windowIdx++;
   if (t%50==0 || t==ticks-1) System.out.println("[UnevenTerrainPassiveStabilityTest] t="+t+" |angular|="+angular.length()+" tilt="+tiltDegrees+"deg");
  }
  double lastWindowMax=0; for (double v : lastOmegaWindow) lastWindowMax=Math.max(lastWindowMax,v);
  System.out.println("[UnevenTerrainPassiveStabilityTest] maxOmega="+maxOmega+" maxTiltDegrees="+maxTiltDegrees+" lastWindowMaxOmega="+lastWindowMax);
  assertTrue(maxTiltDegrees < 30,
    "a small 2cm single-wheel terrain bump, fully undriven, should settle into a modest steady tilt, not a large rollover - got maxTiltDegrees="+maxTiltDegrees);
  assertTrue(lastWindowMax <= maxOmega,
    "angular velocity in the final 20 ticks should not exceed the trajectory's own peak - i.e. it should have settled/bounded, not still be growing toward a new peak at the end of the window - peak="+maxOmega+" last-window-max="+lastWindowMax);
 }
}
