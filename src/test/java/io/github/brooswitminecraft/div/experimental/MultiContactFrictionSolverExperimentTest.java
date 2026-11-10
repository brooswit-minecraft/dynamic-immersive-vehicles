package io.github.brooswitminecraft.div.experimental;
import io.github.brooswitminecraft.div.TireForces;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * EXPERIMENTAL - a whole-vehicle, multi-tick, free-running counterfactual replay (NOT a one-step
 * replay of recorded state) over {@link MultiContactFrictionSolver}, for MINECRAFT-503 review
 * (comments 53017/53018/53019) to inspect and reproduce directly, not just read about in prose.
 *
 * <p><b>Known, deliberate simplification - the open question this leaves unresolved:</b> this
 * harness uses a FIXED per-wheel normal force ({@code mass*gravity/4} every tick) rather than the
 * production {@code PhysicsBridge.suspensionSpring}/{@code suspensionDamper}/
 * {@code TireForces.suspension} formulas against an evolving raycast-equivalent compression. That
 * means it has NO load-transfer coupling (a wheel lifting under roll does not lose normal force/
 * friction budget here, nor does the opposite wheel gain any) - a real stabilizing/destabilizing
 * mechanism the real game has and this harness does not. Review (comment 53019) was explicit that
 * this is not yet the requested faithful production-path reproduction for exactly that reason;
 * the result below should be read as "does the solver alone, in isolation from suspension
 * coupling, resolve the captured instability" (it does not, clearly) rather than "does the real
 * game still roll over with this change" (not yet answered either way by this class).
 *
 * <p>Geometry/mass/inertia are the real captured values (ticket comment 52984/52985). Both
 * variants replayed below start from the same tiny seed angular velocity (real placements are
 * never perfectly symmetric) and are fully undriven/unbraked on flat ground, matching the real
 * capture's own scenario.
 */
class MultiContactFrictionSolverExperimentTest {
 private static final double MASS = 350.0, GRAVITY = 11.0, DT = 0.05, FRICTION = 1.0;
 private static final Vector3d HALF_EXTENTS = new Vector3d(0.42500001192092896, 0.4410937510430813, 1.050000011920929);
 private static Vector3d inertiaBody() {
  double a=HALF_EXTENTS.x, b=HALF_EXTENTS.y, c=HALF_EXTENTS.z;
  return new Vector3d(MASS/3*(b*b+c*c), MASS/3*(a*a+c*c), MASS/3*(a*a+b*b));
 }
 // Real chassisReceipt wheel contact points, body-local, relative to COM (ticket comment 52984).
 private static final Vector3d[] BODY_POINTS = {
  new Vector3d(-0.7293008337849045,-0.903594970703125,-0.25154829325725814),
  new Vector3d(0.6708592596412473,-0.903594970703125,0.2849523710264634),
  new Vector3d(0.5671270377957001,-0.903594970703125,-0.4578394074356993),
  new Vector3d(-0.6295593122761289,-0.903594970703125,0.4669740309086876),
 };
 private static final double NORMAL_PER_WHEEL = MASS*GRAVITY/4;

 private static void applyImpulse(Vector3d point, Vector3d impulse, Vector3d velocity, Vector3d angular,
     Quaterniond orientation, Quaterniond inverse, Vector3d inertiaBody) {
  velocity.add(new Vector3d(impulse).div(MASS));
  Vector3d torqueBody=inverse.transform(new Vector3d(point).cross(impulse));
  angular.add(orientation.transform(new Vector3d(torqueBody.x/inertiaBody.x,torqueBody.y/inertiaBody.y,torqueBody.z/inertiaBody.z)));
 }

 /** The pre-fix per-wheel independent computation this ticket is trying to replace - reproduced
  * here as the "before" oracle (not present in production anymore, which now uses the
  * contact-sampled-slip fix from 691f11b but still the independent mass/wheelCount target). */
 private static double[] oldIndependentLateralTargets(Vector3d[] worldPoints, Vector3d[] rights, Vector3d velocity, Vector3d angular) {
  double[] targets=new double[worldPoints.length];
  for (int i=0;i<worldPoints.length;i++) {
   Vector3d contactVelocity=new Vector3d(angular).cross(worldPoints[i]).add(velocity);
   double sideSpeed=contactVelocity.dot(rights[i]);
   targets[i]=-sideSpeed*MASS/worldPoints.length/DT;
  }
  return targets;
 }

 private static double maxOmegaOverTicks(boolean useNewSolver, int ticks) {
  Vector3d inertiaBody=inertiaBody();
  Vector3d velocity=new Vector3d(0,0,0);
  Vector3d angular=new Vector3d(0.01,0,0.01); // barely-perceptible seed, same order as real placements
  Quaterniond orientation=new Quaterniond();
  double maxOmega=0;
  for (int t=0;t<ticks;t++) {
   Quaterniond inverse=new Quaterniond(orientation).conjugate();
   Vector3d[] worldPoints=new Vector3d[4]; Vector3d[] forwards=new Vector3d[4]; Vector3d[] rights=new Vector3d[4];
   for (int i=0;i<4;i++) {
    worldPoints[i]=orientation.transform(new Vector3d(BODY_POINTS[i]));
    forwards[i]=orientation.transform(new Vector3d(0,0,1));
    rights[i]=new Vector3d(0,1,0).cross(forwards[i]);
   }
   velocity.add(new Vector3d(0,-GRAVITY*DT,0));
   // Stage the (fixed - see class doc) vertical/normal impulses first, same staging order the
   // production code uses.
   for (int i=0;i<4;i++) applyImpulse(worldPoints[i], new Vector3d(0,1,0).mul(NORMAL_PER_WHEEL*DT), velocity, angular, orientation, inverse, inertiaBody);
   if (useNewSolver) {
    var contacts=new MultiContactFrictionSolver.FrictionContact[4];
    for (int i=0;i<4;i++) contacts[i]=new MultiContactFrictionSolver.FrictionContact(worldPoints[i],forwards[i],rights[i],NORMAL_PER_WHEEL,1.0);
    MultiContactFrictionSolver.solveFriction(contacts,MASS,inertiaBody,orientation,inverse,velocity,angular,
      false,0,0,FRICTION,DT,MultiContactFrictionSolver.FRICTION_SOLVER_ITERATIONS);
    // solveFriction folds the resulting impulses into velocity/angular in place.
   } else {
    double[] targets=oldIndependentLateralTargets(worldPoints,rights,velocity,angular);
    for (int i=0;i<4;i++) {
     var tire=TireForces.friction(0,targets[i],NORMAL_PER_WHEEL,FRICTION);
     Vector3d impulse=new Vector3d(rights[i]).mul(tire.lateral()*DT);
     applyImpulse(worldPoints[i], impulse, velocity, angular, orientation, inverse, inertiaBody);
    }
   }
   orientation.integrate(DT, angular.x, angular.y, angular.z); // JOML's own integrator - verified
   // against the manual world-frame quaternion-derivative formula for both an identity and a
   // non-identity starting orientation before relying on it here (ticket comment history).
   maxOmega=Math.max(maxOmega, angular.length());
  }
  return maxOmega;
 }

 @Test void oldIndependentPerWheelTargetReproducesTheRealCapturedRunawayOrderOfMagnitude() {
  double maxOmega=maxOmegaOverTicks(false, 30);
  assertTrue(maxOmega > 2.0,
    "the pre-fix independent mass/wheelCount computation should reproduce a runaway on the same order of magnitude as the real capture (peak |omega| > 2 rad/s within 30 ticks) on this real geometry - got "+maxOmega);
 }

 /** Documents the CURRENT, unresolved state rather than asserting success: the bounded solver
  * measurably helps but does not resolve the instability in this simplified (fixed-normal-force)
  * harness. If a future change to the solver (or a more faithful harness including suspension
  * coupling) changes this materially, update this assertion to match - do not loosen it further
  * without new evidence, and do not tighten it to claim resolution without a faithful harness. */
 @Test void newBoundedSolverMeasurablyHelpsButDoesNotResolveItInThisSimplifiedHarness() {
  double maxOmegaOld=maxOmegaOverTicks(false, 30);
  double maxOmegaNew=maxOmegaOverTicks(true, 30);
  assertTrue(maxOmegaNew < maxOmegaOld,
    "expected at least some measurable improvement over the pre-fix behavior - old="+maxOmegaOld+" new="+maxOmegaNew);
  assertTrue(maxOmegaNew > 2.0,
    "this documents that the solver alone, in this simplified harness, does NOT bring the result back near rest (<2 rad/s) - if it now does, the fidelity gap noted in this class's doc may have been closed and this assertion should be revisited, not just loosened - old="+maxOmegaOld+" new="+maxOmegaNew);
 }
}
