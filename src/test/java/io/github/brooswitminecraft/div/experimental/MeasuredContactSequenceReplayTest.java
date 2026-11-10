package io.github.brooswitminecraft.div.experimental;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The targeted replay requested in ticket comment 53083, built from a local-only measured
 * contact-height sequence for the real window3 capture's onset (ticks 109-120) - NOT a guessed
 * terrain shape. All recorded controls were zero throughout the real window; occupancy unknown.
 *
 * <p>Measured sequence imposed on the FRONT+X wheel (local offset x=+0.375, z=1.25 - the only
 * wheel at positive x among the front pair) ONLY - identified by localOffset, not log row order,
 * per review's explicit caution that row order changes on misses:
 * <ul>
 *  <li>Ticks 90-109 (this test's tick &lt;0, i.e. the starting state): all 4 wheels level, equal
 *  height, zero spread - the real window's own steady baseline before onset.</li>
 *  <li>Tick 110 (this test's tick 0): front+X contact height drops 0.08942m relative to the
 *  other three (measured compression 0.14198-&gt;0.052364, normal 1184.30-&gt;609.23N).</li>
 *  <li>Ticks 111-116 (this test's ticks 1-6): front+X ray MISSES entirely (not a zero-load hit -
 *  a genuine miss).</li>
 *  <li>Tick 117 onward (this test's tick 7+): front+X reacquires contact 0.273658m below the
 *  original reference (measured normal 1850.96N on reacquisition) - held constant here for
 *  simplicity, since the real data's exact post-117 micro-trend for this wheel is not fully
 *  specified at this resolution.</li>
 * </ul>
 *
 * <p>This is an IMPOSED measured contact-sequence diagnostic, not a physically-derived terrain
 * reconstruction - the real terrain's shape, face normals, and the specific reason the ray missed
 * for 6 ticks are all unknown and not modeled. The other 3 wheels' contact state (including the
 * measured rear-X anomalies: normal 7.87N at tick117 despite a hit, near-zero compression/normal
 * at tick118, a miss at ticks119-120) are NOT imposed - they are left to emerge from the solver's
 * own response to the imposed front+X sequence, as a check of whether the model's emergent
 * behavior is at least directionally consistent with what was separately measured there.
 *
 * <p>The real measured response over this window: longitudinal speed 0.447-&gt;0.488 m/s over
 * ticks 90-110, then 0.542-&gt;1.212 m/s over ticks 111-120; up.y 0.9975-&gt;0.9368 over ticks
 * 110-120. This test reports the model's own speed/up.y trajectory under the imposed sequence for
 * direct comparison, rather than asserting a specific match (the starting equilibrium compression
 * for ticks 90-109 was not given at this resolution and is approximated here as the formula's own
 * static equilibrium value, not the real measured one, so an exact numeric match is not expected).
 */
class MeasuredContactSequenceReplayTest {
 private static final double MASS=350.0, GRAVITY=11.0, DT=0.05, FRICTION=1.0, SUSPENSION_TRAVEL=0.3, WHEEL_RADIUS=0.375;
 private static final Vector3d HALF_EXTENTS=new Vector3d(0.42500001192092896,0.4410937510430813,1.050000011920929);
 private static final Vector3d CENTER=new Vector3d(0,0.553593748062849,0.625);
 private static final Vector3d[] LOCAL_OFFSETS={
  new Vector3d(0.35,0.125,-0.0625), new Vector3d(-0.375,0.125,1.25),
  new Vector3d(0.375,0.125,1.25), new Vector3d(-0.375,0.125,-0.0625)};
 private static final int FRONT_PLUS_X=2; // x=+0.375, z=1.25
 private static Vector3d anchorLocal(int i) { return new Vector3d(LOCAL_OFFSETS[i].x, LOCAL_OFFSETS[i].y+SUSPENSION_TRAVEL*.5, LOCAL_OFFSETS[i].z).sub(CENTER); }
 private static Vector3d inertiaBody() {
  double a=HALF_EXTENTS.x,b=HALF_EXTENTS.y,c=HALF_EXTENTS.z;
  return new Vector3d(MASS/3*(b*b+c*c), MASS/3*(a*a+c*c), MASS/3*(a*a+b*b));
 }
 private static double suspensionSpring(int n) { return MASS*GRAVITY/(n*SUSPENSION_TRAVEL*.5); }
 private static double suspensionDamper(double spring, int n) { return 2*Math.sqrt(spring*MASS/n)*.7; }
 private static double suspensionForce(double c, double v, double spring, double damper) { return Math.max(0, c*spring-v*damper); }
 private record WheelState(Vector3d anchorWorld, Vector3d up, boolean hit, double normal, Vector3d point, Vector3d forward, Vector3d right) {}

 /** The measured, imposed per-tick height offset (meters, negative=lower) for the front+X wheel,
  * or null to force a ray MISS regardless of what the geometry would otherwise give. */
 private static Double frontPlusXImposedOffset(int t) {
  if (t==0) return -0.08942;
  if (t>=1 && t<=6) return null; // measured MISS
  return -0.273658; // t>=7, held constant (see class doc)
 }

 @Test void measuredFrontContactLossSequenceProducesConsistentEmergentResponse() {
  int ticks=20; // this test's tick0..19 correspond to real ticks 110..129
  Vector3d velocity=new Vector3d(0,0,0);
  Vector3d angular=new Vector3d(0,0,0);
  Quaterniond orientation=new Quaterniond();
  double bodyPositionY=0.9036;
  Vector3d inertiaBody=inertiaBody();
  double spring=suspensionSpring(4), damper=suspensionDamper(spring,4);
  double initialSpeedGuess=0.488; // real measured speed at tick110 (onset) - seeded as forward velocity, not derived
  velocity.add(orientation.transform(new Vector3d(0,0,initialSpeedGuess)));
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
    if (i==FRONT_PLUS_X) {
     Double imposed=frontPlusXImposedOffset(t);
     if (imposed==null) { wheels[i]=new WheelState(anchorWorld,up,false,0,null,forward,right); continue; }
     double startY=bodyPositionY+anchorWorld.y-imposed;
     double tt=startY/length;
     if (!(tt>=0&&tt<=1)) { wheels[i]=new WheelState(anchorWorld,up,false,0,null,forward,right); continue; }
     double compression=length*(1-tt);
     Vector3d point=new Vector3d(anchorWorld).fma(-tt*length,up);
     Vector3d localVelocity=new Vector3d(angular0).cross(anchorWorld).add(velocity0);
     double normal=Math.min(MASS*GRAVITY, suspensionForce(compression, localVelocity.dot(up), spring, damper));
     wheels[i]=new WheelState(anchorWorld,up,true,normal,point,forward,right);
     continue;
    }
    double startY=bodyPositionY+anchorWorld.y;
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
   Vector3d fwdNow=orientation.transform(new Vector3d(0,0,1));
   double speed=velocity.dot(fwdNow);
   boolean rearMinusXHit=wheels[3].hit();
   double rearMinusXNormal=rearMinusXHit?wheels[3].normal():0;
   System.out.println("[MeasuredContactSequenceReplayTest] t="+t+" (real~"+(110+t)+") speed="+speed+" up.y="+upNow.y
     +" frontPlusXHit="+wheels[FRONT_PLUS_X].hit()+" frontPlusXNormal="+wheels[FRONT_PLUS_X].normal()
     +" rearMinusXHit="+rearMinusXHit+" rearMinusXNormal="+rearMinusXNormal);
  }
  Vector3d upFinal=orientation.transform(new Vector3d(0,1,0));
  Vector3d fwdFinal=orientation.transform(new Vector3d(0,0,1));
  double finalSpeed=velocity.dot(fwdFinal);
  // Directional checks only (not exact-value matches - see class doc on why): the real measured
  // window shows speed INCREASING and up.y DECREASING (tilting) over this onset. The model should
  // show the same DIRECTION of change under the imposed sequence, even if magnitudes differ.
  assertTrue(finalSpeed > initialSpeedGuess,
    "under the measured contact-loss sequence, forward speed should increase (as the real window did), not decrease or stay flat - initial="+initialSpeedGuess+" final="+finalSpeed);
  assertTrue(upFinal.y < 0.999,
    "under the measured contact-loss sequence, the body should tilt measurably (up.y below 0.999), consistent with the real window's own up.y drop to 0.9368 - got "+upFinal.y);
 }
}
