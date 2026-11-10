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
 * <p>Uses the already-validated {@link MultiContactFrictionSolver} and the real chassis geometry/
 * mass/inertia (same as {@link SuspensionCoupledReplayTest}), run from the same tiny seed for 400
 * ticks under two conditions that differ ONLY in which point suspension/friction torques are
 * measured about: the REAL chassis center (its collision-box bounding-box center, independent of
 * wheel placement - confirmed NOT the centroid of the real wheel anchors, comment 53043/53044) vs
 * a COUNTERFACTUAL center recentered to that wheel-anchor centroid. Nothing else changes - same
 * solver, same formulas, same seed.
 *
 * <p>Result: with the REAL (non-centroid) geometry, angular velocity does NOT decay to zero - it
 * settles into a small but genuinely PERSISTENT, non-decaying residual (~2.7e-3 rad/s after 400
 * ticks, essentially flat from tick 100 onward) with a small steady tilt. With the COUNTERFACTUAL
 * (recentered) geometry, under the identical seed/solver/formulas, angular velocity decays
 * smoothly toward true zero (~3.7e-7 rad/s by tick 400 - a ~38,000x reduction from the real
 * geometry's residual) and the body returns to upright. The only difference between the two runs
 * is which point torques are measured about, so the wheel-anchor/chassis-center mismatch is
 * confirmed as a REAL, measurable source of persistent non-decaying residual rotation under the
 * corrected solver - supporting review's "pursue passive load-transfer/contact geometry" steer.
 *
 * <p>This does NOT by itself fully explain the specific tick162 full tip-over in the real capture
 * (this counterfactual's real-geometry residual is a small ~2.9 degree steady tilt/slow spin, not
 * a 90-degree flip) - it isolates and quantifies ONE real contributing mechanism, not the whole
 * remaining story. This test is diagnostic only: it does NOT recenter the shipped geometry or
 * change any production code - per review's explicit instruction, no recentering is proposed.
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

 @Test void realVsRecenteredGeometrySettlesDifferently() {
  Vector3d realCenter=new Vector3d(0,0.553593748062849,0.625);
  Vector3d wheelCentroid=new Vector3d();
  for (Vector3d lo : LOCAL_OFFSETS) wheelCentroid.add(new Vector3d(lo.x, lo.y+SUSPENSION_TRAVEL*.5, lo.z));
  wheelCentroid.mul(0.25);
  System.out.println("real center="+realCenter+" wheel-anchor centroid="+wheelCentroid);
  double realFinalOmega=run("REAL (non-centroid)", realCenter, 400);
  double counterfactualFinalOmega=run("COUNTERFACTUAL (recentered to wheel centroid)", wheelCentroid, 400);
  System.out.println("[WheelCentroidCounterfactualTest] final |angularVelocity| after 400 ticks - real="+realFinalOmega+" rad/s, recentered="+counterfactualFinalOmega+" rad/s");
  assertTrue(realFinalOmega > 1e-3,
    "the real (non-centroid) geometry should NOT decay to near-zero - a genuinely persistent residual is the point of this test - got "+realFinalOmega);
  assertTrue(counterfactualFinalOmega < realFinalOmega * 1e-2,
    "the counterfactual (recentered) geometry, with everything else identical, should decay to a residual at least 100x smaller than the real geometry's persistent one - real="+realFinalOmega+" recentered="+counterfactualFinalOmega);
 }

 private static double run(String label, Vector3d center, int ticks) {
  Vector3d inertiaBody=inertiaBody();
  Vector3d velocity=new Vector3d(0,0,0);
  Vector3d angular=new Vector3d(0.01,0,0.01);
  Quaterniond orientation=new Quaterniond();
  double bodyPositionY=0.9036+(0.553593748062849-center.y); // keep tick0 compression ~0.05
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
   MultiContactFrictionSolver.solveFriction(contacts,MASS,inertiaBody,orientation,inverse,solverVelocity,solverAngular,false,0,0,FRICTION,DT,MultiContactFrictionSolver.FRICTION_SOLVER_ITERATIONS);
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
