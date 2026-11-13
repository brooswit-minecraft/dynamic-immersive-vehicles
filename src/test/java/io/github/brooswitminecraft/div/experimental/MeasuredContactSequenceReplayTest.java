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
 * <p><b>Corrected after review (comment 53085) found the first version did not actually impose
 * the claimed sequence:</b> (1) {@code bodyPositionY=0.9036} gives baseline compression 0.05 (the
 * transient tick0 value from early in this investigation), NOT the ~0.15 equilibrium the class
 * doc claimed - corrected to {@code 0.803593748} (derived the same way the equilibrium CG height
 * was, comment 53072); (2) with the old, wrong bodyPositionY, imposing the measured -0.08942m
 * drop pushed the ray past its 0.675m reach, so the front wheel MISSED at this test's tick 0
 * instead of the measured HIT (compression 0.052364) - fixed by both correcting bodyPositionY and
 * moving the front wheel onto the SAME common raycast sampler every other wheel uses (which also
 * fixes bug (3) below); (3) the front wheel's special-cased branch divided by {@code length}
 * instead of {@code length*up.y} like every other wheel, giving wrong geometry once the body
 * tilts away from upright - now uses one shared sampler function, parameterized only by a
 * per-wheel ground-height override, for all 4 wheels; (4) review's own comment 53083 DID supply
 * the post-117 heights explicitly (-0.273658, -0.227087, -0.171975, -0.118419 at ticks 117-120) -
 * an earlier version of this test incorrectly said they were unavailable and held tick117's value
 * constant; now uses all four real per-tick values, extrapolating (flagged as such) only past
 * tick120 where no further measurement was given. (5) The real local analysis's "speed" was not
 * confirmed to be the forward component specifically - compared against velocity MAGNITUDE here
 * instead of an assumed forward-dot-product, to compare the same observable.
 *
 * <p>This test now asserts the IMPOSED sequence actually produces the claimed per-tick hit/miss
 * state (not just trusts the arithmetic), in addition to reporting the emergent response.
 *
 * <p>Diagnostic only - does not change or depend on anything in {@code PhysicsBridge} itself;
 * the shipped head (55a46fd) is unaffected regardless of this test's result.
 */
class MeasuredContactSequenceReplayTest {
 private static final double MASS=350.0, GRAVITY=11.0, DT=0.05, FRICTION=1.0, SUSPENSION_TRAVEL=0.3, WHEEL_RADIUS=0.375, LENGTH=WHEEL_RADIUS+SUSPENSION_TRAVEL;
 private static final Vector3d HALF_EXTENTS=new Vector3d(0.42500001192092896,0.4410937510430813,1.050000011920929);
 private static final Vector3d CENTER=new Vector3d(0,0.553593748062849,0.625);
 private static final Vector3d[] LOCAL_OFFSETS={
  new Vector3d(0.35,0.125,-0.0625), new Vector3d(-0.375,0.125,1.25),
  new Vector3d(0.375,0.125,1.25), new Vector3d(-0.375,0.125,-0.0625)};
 private static final int FRONT_PLUS_X=2; // x=+0.375, z=1.25
 // Equilibrium bodyPositionY (compression=SUSPENSION_TRAVEL/2=0.15 for all wheels at identity
 // orientation), NOT the transient tick0 value (0.9036, compression 0.05) an earlier version
 // used - derived the same way as the equilibrium CG height (review comment 53072).
 private static final double EQUILIBRIUM_BODY_POSITION_Y=0.803593748062849;
 private static Vector3d anchorLocal(int i) { return new Vector3d(LOCAL_OFFSETS[i].x, LOCAL_OFFSETS[i].y+SUSPENSION_TRAVEL*.5, LOCAL_OFFSETS[i].z).sub(CENTER); }
 private static Vector3d inertiaBody() {
  double a=HALF_EXTENTS.x,b=HALF_EXTENTS.y,c=HALF_EXTENTS.z;
  return new Vector3d(MASS/3*(b*b+c*c), MASS/3*(a*a+c*c), MASS/3*(a*a+b*b));
 }
 private static double suspensionSpring(int n) { return MASS*GRAVITY/(n*SUSPENSION_TRAVEL*.5); }
 private static double suspensionDamper(double spring, int n) { return 2*Math.sqrt(spring*MASS/n)*.7; }
 private static double suspensionForce(double c, double v, double spring, double damper) { return Math.max(0, c*spring-v*damper); }
 private record WheelState(Vector3d anchorWorld, Vector3d up, boolean hit, double compression, double normal, Vector3d point, Vector3d forward, Vector3d right) {}

 /** The measured, imposed per-tick ground-height offset (meters, negative=lower) for the front+X
  * wheel, or null to force a ray MISS - all values from review's own measurement (comment
  * 53083), not guessed. Past tick 10 (real tick120, the last tick review measured), the last
  * known height is held constant - an explicit, disclosed extrapolation, not a measurement. */
 private static Double frontPlusXGroundHeight(int t) {
  return switch (t) {
   case 0 -> -0.08942;
   case 1,2,3,4,5,6 -> null; // measured MISS, ticks 111-116
   case 7 -> -0.273658; // tick117
   case 8 -> -0.227087; // tick118
   case 9 -> -0.171975; // tick119
   default -> -0.118419; // tick120, held constant past this (extrapolation, not measured)
  };
 }

 /** One common raycast sampler for every wheel, parameterized only by a ground-height override
  * (0 for the three unaffected wheels, the measured sequence above for front+X) - review (53085)
  * caught that an earlier version special-cased the front wheel with different (buggy) math
  * instead of sharing this with the other three. */
 private static WheelState raycastWheel(int i, double bodyPositionY, double groundHeight, Quaterniond orientation, Vector3d velocity, Vector3d angular, double spring, double damper) {
  Vector3d anchorWorld=orientation.transform(new Vector3d(anchorLocal(i)));
  Vector3d up=orientation.transform(new Vector3d(0,1,0));
  Vector3d forward=orientation.transform(new Vector3d(0,0,1));
  Vector3d right=new Vector3d(up).cross(forward);
  double startY=bodyPositionY+anchorWorld.y-groundHeight;
  double tt=startY/(LENGTH*up.y);
  if (!(tt>=0&&tt<=1)||!Double.isFinite(tt)) return new WheelState(anchorWorld,up,false,0,0,null,forward,right);
  double compression=LENGTH*(1-tt);
  Vector3d point=new Vector3d(anchorWorld).fma(-tt*LENGTH,up);
  Vector3d localVelocity=new Vector3d(angular).cross(anchorWorld).add(velocity);
  double normal=Math.min(MASS*GRAVITY, suspensionForce(compression, localVelocity.dot(up), spring, damper));
  return new WheelState(anchorWorld,up,true,compression,normal,point,forward,right);
 }

 @Test void measuredFrontContactLossSequenceProducesConsistentEmergentResponse() {
  int ticks=11; // this test's tick0..10 correspond to real ticks 110..120 - the full measured range
  Vector3d velocity=new Vector3d(0,0,0);
  Vector3d angular=new Vector3d(0,0,0);
  Quaterniond orientation=new Quaterniond();
  double bodyPositionY=EQUILIBRIUM_BODY_POSITION_Y;
  Vector3d inertiaBody=inertiaBody();
  double spring=suspensionSpring(4), damper=suspensionDamper(spring,4);
  double initialSpeedGuess=0.488; // real measured speed at tick110 (onset) - magnitude, see class doc
  velocity.add(orientation.transform(new Vector3d(0,0,initialSpeedGuess)));
  for (int t=0;t<ticks;t++) {
   Quaterniond inverse=new Quaterniond(orientation).conjugate();
   Vector3d velocity0=new Vector3d(velocity), angular0=new Vector3d(angular);
   WheelState[] wheels=new WheelState[4];
   for (int i=0;i<4;i++) {
    if (i==FRONT_PLUS_X) {
     Double groundHeight=frontPlusXGroundHeight(t);
     wheels[i]=(groundHeight==null) ? new WheelState(null,null,false,0,0,null,null,null)
       : raycastWheel(i,bodyPositionY,groundHeight,orientation,velocity0,angular0,spring,damper);
    } else {
     wheels[i]=raycastWheel(i,bodyPositionY,0,orientation,velocity0,angular0,spring,damper);
    }
   }
   // Verify the imposed sequence actually produced the claimed hit/miss state - not just trust
   // the arithmetic (review 53085's core point).
   boolean expectHit = t!=1 && t!=2 && t!=3 && t!=4 && t!=5 && t!=6;
   assertEquals(expectHit, wheels[FRONT_PLUS_X].hit(),
     "t="+t+" (real~"+(110+t)+"): front+X hit state should match the measured sequence - expected hit="+expectHit+", got "+wheels[FRONT_PLUS_X].hit());
   if (t==0) assertEquals(0.052364, wheels[FRONT_PLUS_X].compression(), 0.01,
     "t=0 (real tick110): front+X compression should be close to the measured 0.052364 - got "+wheels[FRONT_PLUS_X].compression());
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
   for (int k=0;k<active.size();k++) { WheelState w=active.get(k); contacts[k]=new MultiContactFrictionSolver.FrictionContact(w.point(),w.forward(),w.right(),w.normal(),1.0,0.0); }
   Vector3d bf=new Vector3d(solverVelocity), ba=new Vector3d(solverAngular);
   MultiContactFrictionSolver.solveFriction(contacts,MASS,inertiaBody,orientation,inverse,solverVelocity,solverAngular,0,0,FRICTION,1.0,DT,MultiContactFrictionSolver.FRICTION_SOLVER_ITERATIONS);
   velocity.add(new Vector3d(solverVelocity).sub(bf));
   angular.add(new Vector3d(solverAngular).sub(ba));
   bodyPositionY += velocity.y*DT;
   orientation.integrate(DT, angular.x, angular.y, angular.z);
   Vector3d upNow=orientation.transform(new Vector3d(0,1,0));
   double speedMagnitude=velocity.length();
   boolean rearMinusXHit=wheels[3].hit();
   double rearMinusXNormal=rearMinusXHit?wheels[3].normal():0;
   System.out.println("[MeasuredContactSequenceReplayTest] t="+t+" (real~"+(110+t)+") |velocity|="+speedMagnitude+" up.y="+upNow.y
     +" frontPlusXHit="+wheels[FRONT_PLUS_X].hit()+" frontPlusXNormal="+wheels[FRONT_PLUS_X].normal()
     +" rearMinusXHit="+rearMinusXHit+" rearMinusXNormal="+rearMinusXNormal);
  }
  Vector3d upFinal=orientation.transform(new Vector3d(0,1,0));
  double finalSpeedMagnitude=velocity.length();
  // Directional checks only (not exact-value matches - see class doc on why): the real measured
  // window shows speed INCREASING and up.y DECREASING (tilting) over this onset.
  assertTrue(finalSpeedMagnitude > initialSpeedGuess,
    "under the measured contact-loss sequence, |velocity| should increase (as the real window did), not decrease or stay flat - initial="+initialSpeedGuess+" final="+finalSpeedMagnitude);
  assertTrue(upFinal.y < 0.999,
    "under the measured contact-loss sequence, the body should tilt measurably (up.y below 0.999), consistent with the real window's own up.y drop to 0.9368 - got "+upFinal.y);
 }
}
