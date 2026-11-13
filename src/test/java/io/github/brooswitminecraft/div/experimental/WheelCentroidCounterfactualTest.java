package io.github.brooswitminecraft.div.experimental;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The smallest discriminating counterfactual test requested (ticket comment 53064) to tell apart
 * "passive load-transfer/contact-geometry" from other candidate explanations for the residual
 * instability real post-fix gameplay still showed in an undriven window (comment 53062/53064:
 * window3, zero logged controls, tips past 90 degrees by tick162) - without a new user capture or
 * any change to the shipped fix. Review's own torque-closure and finite-step energy analysis of
 * that real capture already ruled out the OLD friction-energy-injection mechanism (tire-impulse
 * KE was negative at every analyzed step of that window - friction was damping, not pumping) and
 * pointed at load-transfer/contact geometry instead.
 *
 * <p><b>Corrected after review (comment 53067) found the first version of this test was NOT
 * actually controlled:</b> it changed the REFERENCE CENTER's y-coordinate too, which (1) had the
 * wrong sign in its own bodyPositionY compensation (verified: should be
 * {@code 0.35+center.y}, not the original {@code 0.9036+(0.553593748-center.y)} - the two happen
 * to agree only at the real center's own y, by construction, then diverge for any other y), so
 * tick0 ray reach/contact count silently differed between the two runs instead of matching, and
 * (2) even with that fixed, changing center.y also changes each wheel's VERTICAL lever arm
 * relative to the reference point - a genuinely different physical scenario (effectively moving
 * the COM vertically while keeping the same inertia tensor, not merely "which point torques are
 * measured about"). The claimed ~38,000x ratio was also an arithmetic error (2.7e-3/3.7e-7 is
 * ~7300x, not ~38,000x) - retracted.
 *
 * <p>This version isolates ONLY the horizontal (x,z) mismatch - the real chassis center's x,z
 * vs the wheel-anchor centroid's x,z, with the reference point's y-coordinate (and therefore
 * every wheel's vertical lever arm, tick0 ray reach, and tick0 compression/contact count)
 * UNCHANGED between the two runs - verified equal via an explicit tick0 assertion, not assumed.
 * Uses the already-validated {@link MultiContactFrictionSolver} and the real chassis geometry/
 * mass/inertia (same as {@link SuspensionCoupledReplayTest}), run from the same tiny seed for 400
 * ticks.
 *
 * <p>Result: with the REAL (non-centroid in x,z) geometry, angular velocity does NOT decay to
 * zero - it settles into a small but genuinely PERSISTENT, non-decaying residual. With the
 * COUNTERFACTUAL (x,z-recentered to the wheel centroid, y unchanged) geometry, under the
 * identical seed/solver/formulas/tick0 contact state, angular velocity decays much further toward
 * zero. See the printed numbers in the test output for the exact, current ratio - not restated
 * here as a fixed claim, given the previous version's numbers were wrong.
 *
 * <p>This does NOT by itself fully explain the specific tick162 full tip-over in the real capture
 * - it isolates and quantifies ONE real contributing mechanism (the horizontal centroid mismatch
 * specifically), not the whole remaining story, and not the vertical-COM question review
 * separated out as a different physical counterfactual (not attempted here). This test is
 * diagnostic only: it does NOT recenter the shipped geometry or change any production code - per
 * review's explicit instruction, no recentering is proposed.
 */
public class WheelCentroidCounterfactualTest {
 private static final double MASS=350.0, GRAVITY=11.0, DT=0.05, FRICTION=1.0, SUSPENSION_TRAVEL=0.3, WHEEL_RADIUS=0.375;
 private static final Vector3d HALF_EXTENTS=new Vector3d(0.42500001192092896,0.4410937510430813,1.050000011920929);
 private static final Vector3d[] LOCAL_OFFSETS={
  new Vector3d(0.35,0.125,-0.0625), new Vector3d(-0.375,0.125,1.25),
  new Vector3d(0.375,0.125,1.25), new Vector3d(-0.375,0.125,-0.0625)};
 private static Vector3d inertiaBody() {
  double a=HALF_EXTENTS.x,b=HALF_EXTENTS.y,c=HALF_EXTENTS.z;
  return new Vector3d(MASS/3*(b*b+c*c), MASS/3*(a*a+c*c), MASS/3*(a*a+b*b));
 }
 private static double suspensionSpring(int n) { return MASS*GRAVITY/(n*SUSPENSION_TRAVEL*.5); }
 private static double suspensionDamper(double spring, int n) { return 2*Math.sqrt(spring*MASS/n)*.7; }
 private static double suspensionForce(double c, double v, double spring, double damper) { return Math.max(0, c*spring-v*damper); }
 private record WheelState(Vector3d anchorWorld, Vector3d up, boolean hit, double compression, double normal, Vector3d point, Vector3d forward, Vector3d right) {}

 private static final double REAL_CENTER_Y=0.553593748062849; // unchanged in BOTH runs below
 private static final double BODY_POSITION_Y=0.9036; // unchanged in BOTH runs - y-geometry is identical

 @Test void realVsHorizontallyRecenteredGeometrySettlesDifferently() {
  Vector3d realCenter=new Vector3d(0,REAL_CENTER_Y,0.625);
  Vector3d horizontallyRecenteredCenter=new Vector3d(-0.0062500000000000056,REAL_CENTER_Y,0.59375); // wheel-anchor centroid's x,z; y UNCHANGED from realCenter
  System.out.println("real center="+realCenter+" horizontally-recentered (y unchanged)="+horizontallyRecenteredCenter);
  double[] realTick0=tick0Compressions(realCenter);
  double[] counterfactualTick0=tick0Compressions(horizontallyRecenteredCenter);
  assertArrayEquals(realTick0, counterfactualTick0, 1e-9,
    "tick0 compression per wheel must be IDENTICAL between the two runs (same y-geometry, same contact count) - real="+java.util.Arrays.toString(realTick0)+" counterfactual="+java.util.Arrays.toString(counterfactualTick0));
  double realFinalOmega=run("REAL (non-centroid x,z)", realCenter, 400);
  double counterfactualFinalOmega=run("COUNTERFACTUAL (x,z-recentered, y unchanged)", horizontallyRecenteredCenter, 400);
  System.out.println("[WheelCentroidCounterfactualTest] final |angularVelocity| after 400 ticks - real="+realFinalOmega+" rad/s, recentered="+counterfactualFinalOmega+" rad/s (ratio "+(realFinalOmega/counterfactualFinalOmega)+"x)");
  assertTrue(realFinalOmega > 1e-3,
    "the real (non-centroid) geometry should NOT decay to near-zero - a genuinely persistent residual is the point of this test - got "+realFinalOmega);
  assertTrue(counterfactualFinalOmega < realFinalOmega * 0.5,
    "the horizontally-recentered geometry, with y-geometry/tick0 contact state verified identical, should decay further than the real geometry's persistent residual - real="+realFinalOmega+" recentered="+counterfactualFinalOmega);
 }

 /** tick0 COMPRESSION ONLY for all 4 wheels, for the explicit "is this actually controlled" check
  * review asked for. Review (comment 53069) correctly caught that an earlier version of this
  * helper also claimed NORMAL-force equality using a hardcoded normalSpeed=0, which does not
  * match {@code run}'s real initial state ({@code run} seeds a nonzero angular velocity, so its
  * actual tick0 localVelocity.dot(up) - and therefore the damper-driven part of normal - is
  * nonzero and genuinely DIFFERENT between the two centers, since the same angular seed crossed
  * with two different anchorWorld positions gives different values; that difference is part of
  * what this test is exploring, not a confound to assert away). Narrowed to compression, which
  * depends only on geometry/position (bodyPositionY, anchorLocal.y) and is correctly identical
  * between the two runs regardless of the angular seed. */
 private static double[] tick0Compressions(Vector3d center) {
  double[] result=new double[4];
  for (int i=0;i<4;i++) {
   Vector3d anchorLocal=new Vector3d(LOCAL_OFFSETS[i].x, LOCAL_OFFSETS[i].y+SUSPENSION_TRAVEL*.5, LOCAL_OFFSETS[i].z).sub(center);
   double length=WHEEL_RADIUS+SUSPENSION_TRAVEL;
   double startY=BODY_POSITION_Y+anchorLocal.y; // orientation=identity at t=0, world==local
   double tt=startY/length;
   result[i]=length*(1-tt);
  }
  return result;
 }

 private static double run(String label, Vector3d center, int ticks) {
  Vector3d inertiaBody=inertiaBody();
  Vector3d velocity=new Vector3d(0,0,0);
  Vector3d angular=new Vector3d(0.01,0,0.01);
  Quaterniond orientation=new Quaterniond();
  double bodyPositionY=BODY_POSITION_Y; // identical in both runs - only center.x/center.z differ
  double spring=suspensionSpring(4), damper=suspensionDamper(spring,4);
  for (int t=0;t<ticks;t++) {
   Quaterniond inverse=new Quaterniond(orientation).conjugate();
   Vector3d velocity0=new Vector3d(velocity), angular0=new Vector3d(angular);
   WheelState[] wheels=new WheelState[4];
   for (int i=0;i<4;i++) {
    Vector3d anchorLocal=new Vector3d(LOCAL_OFFSETS[i].x, LOCAL_OFFSETS[i].y+SUSPENSION_TRAVEL*.5, LOCAL_OFFSETS[i].z).sub(center);
    double length=WHEEL_RADIUS+SUSPENSION_TRAVEL;
    Vector3d anchorWorld=orientation.transform(new Vector3d(anchorLocal));
    Vector3d up=orientation.transform(new Vector3d(0,1,0));
    Vector3d forward=orientation.transform(new Vector3d(0,0,1));
    Vector3d right=new Vector3d(up).cross(forward);
    double startY=bodyPositionY+anchorWorld.y;
    double tt=startY/(length*up.y);
    if (!(tt>=0&&tt<=1)||!Double.isFinite(tt)) { wheels[i]=new WheelState(anchorWorld,up,false,0,0,null,forward,right); continue; }
    double compression=length*(1-tt);
    Vector3d point=new Vector3d(anchorWorld).fma(-tt*length,up);
    Vector3d localVelocity=new Vector3d(angular0).cross(anchorWorld).add(velocity0);
    double normal=Math.min(MASS*GRAVITY, suspensionForce(compression, localVelocity.dot(up), spring, damper));
    wheels[i]=new WheelState(anchorWorld,up,true,compression,normal,point,forward,right);
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
   MultiContactFrictionSolver.solveFriction(contacts,MASS,inertiaBody,orientation,inverse,solverVelocity,solverAngular,0.0,0,0,FRICTION,1.0,DT,MultiContactFrictionSolver.FRICTION_SOLVER_ITERATIONS);
   velocity.add(new Vector3d(solverVelocity).sub(bf));
   angular.add(new Vector3d(solverAngular).sub(ba));
   bodyPositionY += velocity.y*DT;
   orientation.integrate(DT, angular.x, angular.y, angular.z);
   if (t%50==0 || t==ticks-1) {
    Vector3d up=orientation.transform(new Vector3d(0,1,0));
    System.out.println(label+" t="+t+" angular="+angular+" |angular|="+angular.length()+" up="+up);
   }
  }
  return angular.length();
 }
}
