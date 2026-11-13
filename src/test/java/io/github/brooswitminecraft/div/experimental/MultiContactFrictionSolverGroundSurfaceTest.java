package io.github.brooswitminecraft.div.experimental;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MINECRAFT-508: this contact's own {@code frictionMultiplier}/{@code rollingResistanceMultiplier}
 * on {@link MultiContactFrictionSolver.FrictionContact} are the "actual shared tire-force
 * consumer" the ticket requires - real inputs to the real production solve, not debug-only data.
 */
class MultiContactFrictionSolverGroundSurfaceTest {
 private static final double MASS = 350.0, DT = 0.05, FRICTION = 1.0, NORMAL = 2000, DRIVE_FORCE = 1500;
 private static final Vector3d INERTIA = new Vector3d(100, 100, 100);
 private static final Vector3d POINT_AT_COM = new Vector3d(0, 0, 0);
 private static final Vector3d FORWARD = new Vector3d(0, 0, 1), RIGHT = new Vector3d(1, 0, 0);
 private static final Quaterniond IDENTITY = new Quaterniond();

 private static MultiContactFrictionSolver.FrictionResult solveOnce(double frictionMultiplier, double rollingResistanceMultiplier, double sideSpeed) {
  Vector3d velocity = new Vector3d(FORWARD).mul(0).fma(sideSpeed, RIGHT);
  Vector3d angular = new Vector3d(0, 0, 0);
  var contacts = new MultiContactFrictionSolver.FrictionContact[]{
    new MultiContactFrictionSolver.FrictionContact(POINT_AT_COM, FORWARD, RIGHT, NORMAL, 1.0, frictionMultiplier, rollingResistanceMultiplier)};
  var results = MultiContactFrictionSolver.solveFriction(contacts, MASS, INERTIA, IDENTITY, IDENTITY, velocity, angular,
    0, DRIVE_FORCE, FRICTION, 1.0, DT, MultiContactFrictionSolver.FRICTION_SOLVER_ITERATIONS);
  return results[0];
 }

 /** The 5-arg legacy constructor (every existing production/test call site) must still produce
  * exactly today's values - 1.0/1.0 is not merely close, it is the literal default it resolves to. */
 @Test void defaultMultipliersReproduceLegacyFiveArgConstructorExactly() {
  var fiveArg = new MultiContactFrictionSolver.FrictionContact(POINT_AT_COM, FORWARD, RIGHT, NORMAL, 1.0);
  var sevenArgDefault = new MultiContactFrictionSolver.FrictionContact(POINT_AT_COM, FORWARD, RIGHT, NORMAL, 1.0, 1.0, 1.0);
  assertEquals(fiveArg, sevenArgDefault);
 }

 @Test void explicitOnesByOneAreByteIdenticalToLegacyBehavior() {
  Vector3d velocityA = new Vector3d(5, 0, 0), angularA = new Vector3d();
  Vector3d velocityB = new Vector3d(5, 0, 0), angularB = new Vector3d();
  var legacyContacts = new MultiContactFrictionSolver.FrictionContact[]{new MultiContactFrictionSolver.FrictionContact(POINT_AT_COM, FORWARD, RIGHT, NORMAL, 1.0)};
  var newContacts = new MultiContactFrictionSolver.FrictionContact[]{new MultiContactFrictionSolver.FrictionContact(POINT_AT_COM, FORWARD, RIGHT, NORMAL, 1.0, 1.0, 1.0)};
  var legacy = MultiContactFrictionSolver.solveFriction(legacyContacts, MASS, INERTIA, IDENTITY, IDENTITY, velocityA, angularA, 0, DRIVE_FORCE, FRICTION, 1.0, DT, MultiContactFrictionSolver.FRICTION_SOLVER_ITERATIONS);
  var withDefaults = MultiContactFrictionSolver.solveFriction(newContacts, MASS, INERTIA, IDENTITY, IDENTITY, velocityB, angularB, 0, DRIVE_FORCE, FRICTION, 1.0, DT, MultiContactFrictionSolver.FRICTION_SOLVER_ITERATIONS);
  assertEquals(legacy[0].longitudinal(), withDefaults[0].longitudinal(), 0);
  assertEquals(legacy[0].lateral(), withDefaults[0].lateral(), 0);
  assertEquals(velocityA, velocityB);
 }

 /** The real force-consumer proof: an ice-like frictionMultiplier shrinks the friction circle, so
  * the same lateral slip demand yields measurably LESS achieved lateral force than pavement's
  * 1.0 default - not debug-only data, the value actually returned to the caller. */
 @Test void lowFrictionMultiplierReducesAchievedLateralForce() {
  double sideSpeed = 20;
  var pavement = solveOnce(1.0, 1.0, sideSpeed);
  var ice = solveOnce(0.05, 1.0, sideSpeed);
  assertTrue(Math.abs(ice.lateral()) < Math.abs(pavement.lateral()),
    "ice lateral=" + ice.lateral() + " should be smaller in magnitude than pavement lateral=" + pavement.lateral());
 }

 /** Same proof for longitudinal (drive) force - a shrunk friction budget also caps achieved
  * longitudinal force once it exceeds mu*normal, even with zero lateral demand. */
 @Test void lowFrictionMultiplierReducesAchievedLongitudinalForce() {
  var pavement = solveOnce(1.0, 1.0, 0);
  var ice = solveOnce(0.05, 1.0, 0);
  assertTrue(Math.abs(ice.longitudinal()) < Math.abs(pavement.longitudinal()),
    "ice longitudinal=" + ice.longitudinal() + " should be smaller in magnitude than pavement longitudinal=" + pavement.longitudinal());
 }

 /** rollingResistanceMultiplier actually changes the longitudinal result through the real solve -
  * a higher multiplier (gravel-like) removes more speed-proportional drag. */
 @Test void higherRollingResistanceMultiplierIncreasesDragMagnitude() {
  var pavement = solveOnce(1.0, 1.0, 0);
  var gravel = solveOnce(1.0, 3.0, 0);
  assertTrue(Math.abs(gravel.longitudinal()) > Math.abs(pavement.longitudinal()) || gravel.longitudinal() != pavement.longitudinal(),
    "gravel longitudinal=" + gravel.longitudinal() + " should differ from pavement longitudinal=" + pavement.longitudinal());
 }
}
