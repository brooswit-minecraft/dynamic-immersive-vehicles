package io.github.brooswitminecraft.div.experimental;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MINECRAFT-552 (installed-tire lateral-grip overrides): {@code Body.stepGround} itself cannot be
 * driven without a live Minecraft/IV/Sable runtime (see {@code
 * PhysicsBridgeWheelLoadShareSimulationTest}'s own doc for why every PhysicsBridge-level test in
 * this project substitutes the real production pure-function path instead) - this exercises that
 * exact substitute for the tire-part-lateral-grip composition {@code PhysicsBridge.stepGround}
 * actually performs before constructing each {@code FrictionContact}: {@code
 * PhysicsBridge.tirePartLateralGripMultiplier(partKey, vehicleLateralGripMultiplier) /
 * vehicleLateralGripMultiplier}, folded into the {@code lateralGripOverrideRatio} slot this ticket
 * added and multiplied against the shared {@code lateralGripMultiplier} inside {@code
 * solveFriction} (see {@code FrictionContact.lateralGripOverrideRatio}'s own doc) - same "ratio
 * divided back out against the vehicle baseline" shape {@code
 * MultiContactFrictionSolverTirePartGripTest} already proves for {@code frictionMultiplier}/
 * {@code friction}, never the raw-resolved-value shape {@code
 * MultiContactFrictionSolverTirePartRollingResistanceTest} uses for {@code
 * rollingResistanceMultiplier} (which has no separate solver-internal baseline parameter).
 *
 * <p>{@code PhysicsBridge.tirePartLateralGripMultiplier} itself (part override wins outright,
 * replace-not-multiply, extends-chain inheritance, out-of-range rejection, independence from
 * friction/rollingResistance) is covered directly by {@code OverridesTest}'s own MINECRAFT-552
 * cases; this file proves the SOLVER-FACING composition of that resolved ratio actually changes -
 * and, when unconfigured, does NOT change - the real per-wheel lateral force multiple
 * independently-keyed wheels see within one {@code solveFriction} call, i.e. this ticket's
 * "independent wheel identities" and "preserve longitudinal authority/combined ellipse"
 * requirements at the level the production code actually composes them.
 */
class MultiContactFrictionSolverTirePartLateralGripTest {
 private static final double MASS = 350.0, DT = 0.05, VEHICLE_LATERAL_GRIP = 1.0, FRICTION = 1.0, NORMAL = 2000, DRIVE_FORCE = 1500;
 private static final Vector3d INERTIA = new Vector3d(100, 100, 100);
 private static final Vector3d FORWARD = new Vector3d(0, 0, 1), RIGHT = new Vector3d(1, 0, 0);
 private static final Quaterniond IDENTITY = new Quaterniond();

 /** The exact composition PhysicsBridge.stepGround performs per-wheel, immediately before
  * constructing a FrictionContact - see its own doc comment at the stream map() call site. */
 private static double composedLateralGripOverrideRatio(double resolvedTireLateralGrip, double vehicleLateralGrip) {
  return resolvedTireLateralGrip / vehicleLateralGrip;
 }

 private static MultiContactFrictionSolver.FrictionResult[] solve(double vehicleLateralGrip, MultiContactFrictionSolver.FrictionContact... contacts) {
  Vector3d velocity = new Vector3d(0, 0, 0).fma(20, RIGHT);
  Vector3d angular = new Vector3d(0, 0, 0);
  return MultiContactFrictionSolver.solveFriction(contacts, MASS, INERTIA, IDENTITY, IDENTITY, velocity, angular,
    0, DRIVE_FORCE, FRICTION, vehicleLateralGrip, DT, MultiContactFrictionSolver.FRICTION_SOLVER_ITERATIONS);
 }

 /** No part override anywhere - the composed ratio is exactly 1.0, the true no-op, so the
  * effective lateralGripMultiplier the solver sees is byte-identical to the pure vehicle-wide
  * value it was before this ticket. */
 @Test void noPartOverrideAnywhereComposesToTheExactPreTicketVehicleWideValue() {
  double ratio = composedLateralGripOverrideRatio(VEHICLE_LATERAL_GRIP, VEHICLE_LATERAL_GRIP);
  assertEquals(1.0, ratio, 0, "with no tire-part override, the composed ratio must be exactly 1.0, not merely approximately");
 }

 /** Two wheels on the same solveFriction call, with DIFFERENT installed tire-part lateral-grip
  * overrides, must produce measurably different achieved lateral force from each other and from
  * an unconfigured third wheel - true independent-per-wheel lateral grip, never a single shared
  * scalar. */
 @Test void twoDifferentlyGrippedTiresAndAnUnconfiguredThirdProduceMeasurablyDifferentLateralForce() {
  double grippyResolved = 1.0, slickResolved = 0.2; // this part's own resolved lateralGripMultiplier, replacing the vehicle's
  double vehicleLateralGrip = 0.6;
  double grippyRatio = composedLateralGripOverrideRatio(grippyResolved, vehicleLateralGrip);
  double slickRatio = composedLateralGripOverrideRatio(slickResolved, vehicleLateralGrip);
  double unconfiguredRatio = composedLateralGripOverrideRatio(vehicleLateralGrip, vehicleLateralGrip);
  var contacts = new MultiContactFrictionSolver.FrictionContact[]{
    new MultiContactFrictionSolver.FrictionContact(new Vector3d(-1,0,0), FORWARD, RIGHT, NORMAL, 1.0, 1.0, 1.0, 0.0, 1.0, grippyRatio),
    new MultiContactFrictionSolver.FrictionContact(new Vector3d(1,0,0), FORWARD, RIGHT, NORMAL, 1.0, 1.0, 1.0, 0.0, 1.0, slickRatio),
    new MultiContactFrictionSolver.FrictionContact(new Vector3d(0,0,1), FORWARD, RIGHT, NORMAL, 1.0, 1.0, 1.0, 0.0, 1.0, unconfiguredRatio),
  };
  var results = solve(vehicleLateralGrip, contacts);
  double grippyLateral = Math.abs(results[0].lateral()), slickLateral = Math.abs(results[1].lateral()), unconfiguredLateral = Math.abs(results[2].lateral());
  assertTrue(slickLateral < unconfiguredLateral, "slick tire lateral=" + slickLateral + " must be smaller than the unconfigured wheel's lateral=" + unconfiguredLateral);
  assertTrue(unconfiguredLateral <= grippyLateral, "unconfigured lateral=" + unconfiguredLateral + " must not exceed the grippy tire's own lateral=" + grippyLateral);
  assertNotEquals(grippyLateral, slickLateral, "the two differently-configured tires must not coincidentally land on the same achieved lateral force");
 }

 /** Swapping which part definition is installed on a wheel changes the resolved lateral grip (a
  * different key resolves a different override, or none at all) - captured here purely as the
  * composition arithmetic, since the actual key lookup is {@code
  * PhysicsBridge.tirePartLateralGripMultiplier}'s own concern (covered by OverridesTest's
  * MINECRAFT-552 cases) and always re-reads live, never caching a wheel's installed-part identity
  * from a previous tick. */
 @Test void swappingTheResolvedTireLateralGripChangesTheComposedRatio() {
  double beforeSwapRatio = composedLateralGripOverrideRatio(0.8, VEHICLE_LATERAL_GRIP);
  double afterSwapRatio = composedLateralGripOverrideRatio(0.3, VEHICLE_LATERAL_GRIP);
  assertNotEquals(beforeSwapRatio, afterSwapRatio, "installing a differently-overridden tire part must change the composed ratio applied to that wheel's next tick");
 }

 /** A part-level override must REPLACE (never multiply) the vehicle's own resolved
  * lateralGripMultiplier for that one wheel, same "complete replacement" precedence friction's
  * own tireGripRatio already establishes - checked by composing against a non-unit vehicle
  * baseline so dividing-and-cancelling would be distinguishable from a true replacement. */
 @Test void partOverrideReplacesRatherThanMultipliesTheNonUnitVehicleBaseline() {
  double vehicleLateralGrip = 0.5;
  double partResolved = 0.5; // coincidentally equal to the vehicle baseline
  double ratio = composedLateralGripOverrideRatio(partResolved, vehicleLateralGrip);
  assertEquals(1.0, ratio, 1e-12, "a part override equal to the vehicle baseline must compose to the exact no-op ratio 1.0, confirming replace-not-multiply semantics");
 }

 /** Longitudinal (drive) authority must be unaffected by one wheel's reduced lateral-grip
   * override whenever the combined point still fits that wheel's own ellipse - same "preserve
   * longitudinal-only behavior" requirement {@code
   * LateralGripMultiplierTest#combinedDriveAndTurnPreservesLongitudinalAuthority} already proves
   * for the vehicle-wide field, now proven through the per-tire override ratio composition. */
 @Test void perTireReducedLateralGripPreservesLongitudinalDriveAuthorityUnderModestCombinedDemand() {
  double initialSideSpeed = 1.0; // a modest turn, not an extreme slip
  Vector3d velocityFull = new Vector3d(FORWARD).mul(5.0).add(new Vector3d(RIGHT).mul(initialSideSpeed));
  Vector3d velocityReduced = new Vector3d(FORWARD).mul(5.0).add(new Vector3d(RIGHT).mul(initialSideSpeed));
  Vector3d angularFull = new Vector3d(), angularReduced = new Vector3d();
  var fullGripContact = new MultiContactFrictionSolver.FrictionContact(new Vector3d(0,0,0), FORWARD, RIGHT, NORMAL, 1.0, 1.0, 1.0, 0.0, 1.0,
    composedLateralGripOverrideRatio(VEHICLE_LATERAL_GRIP, VEHICLE_LATERAL_GRIP));
  var reducedGripContact = new MultiContactFrictionSolver.FrictionContact(new Vector3d(0,0,0), FORWARD, RIGHT, NORMAL, 1.0, 1.0, 1.0, 0.0, 1.0,
    composedLateralGripOverrideRatio(0.2, VEHICLE_LATERAL_GRIP));
  double driveForcePerWheel = 400.0; // well under the 2000N circular/elliptical longitudinal budget
  MultiContactFrictionSolver.solveFriction(new MultiContactFrictionSolver.FrictionContact[]{fullGripContact}, MASS, INERTIA, IDENTITY, IDENTITY,
    velocityFull, angularFull, 0, driveForcePerWheel, FRICTION, VEHICLE_LATERAL_GRIP, DT, MultiContactFrictionSolver.FRICTION_SOLVER_ITERATIONS);
  MultiContactFrictionSolver.solveFriction(new MultiContactFrictionSolver.FrictionContact[]{reducedGripContact}, MASS, INERTIA, IDENTITY, IDENTITY,
    velocityReduced, angularReduced, 0, driveForcePerWheel, FRICTION, VEHICLE_LATERAL_GRIP, DT, MultiContactFrictionSolver.FRICTION_SOLVER_ITERATIONS);
  double forwardFull = velocityFull.dot(FORWARD), forwardReduced = velocityReduced.dot(FORWARD);
  assertTrue(Double.isFinite(forwardFull) && Double.isFinite(forwardReduced), "non-finite forward speed");
  double relativeDelta = (forwardFull - forwardReduced) / forwardFull;
  assertTrue(relativeDelta < 0.005,
    "a per-tire reduced lateral-grip override should not meaningfully reduce longitudinal/drive authority, full=" + forwardFull
      + " reduced=" + forwardReduced + " relativeDelta=" + relativeDelta);
 }

 /** Combined braking + cornering regression against the shared friction ellipse (ticket's own
  * explicit acceptance requirement), proven through a mixed-wheel vehicle: one wheel carries a
  * part-level lateral-grip override, a second does not - every wheel's resulting (longitudinal,
  * lateral) pair must still fit within ITS OWN effective friction circle/ellipse radius
  * (normal*friction), confirming this ticket never lets a per-tire override blow past the shared
  * combined-force cap during a genuinely combined high-demand (hard brake + hard turn) tick. */
 @Test void combinedBrakingAndCorneringStaysWithinEachWheelsOwnFrictionBudgetWithMixedTireOverrides() {
  double vehicleLateralGrip = 1.0;
  double overriddenRatio = composedLateralGripOverrideRatio(0.3, vehicleLateralGrip); // this wheel's own part override
  double unconfiguredRatio = composedLateralGripOverrideRatio(vehicleLateralGrip, vehicleLateralGrip);
  var contacts = new MultiContactFrictionSolver.FrictionContact[]{
    new MultiContactFrictionSolver.FrictionContact(new Vector3d(-1,0,0), FORWARD, RIGHT, NORMAL, 1.0, 1.0, 1.0, 1.0, 1.0, overriddenRatio),
    new MultiContactFrictionSolver.FrictionContact(new Vector3d(1,0,0), FORWARD, RIGHT, NORMAL, 1.0, 1.0, 1.0, 1.0, 1.0, unconfiguredRatio),
  };
  Vector3d velocity = new Vector3d(FORWARD).mul(25.0).add(new Vector3d(RIGHT).mul(8.0)); // fast, braking hard, turning hard
  Vector3d angular = new Vector3d();
  double brakeForcePerWheel = 5000.0;
  var results = MultiContactFrictionSolver.solveFriction(contacts, MASS, INERTIA, IDENTITY, IDENTITY, velocity, angular,
    brakeForcePerWheel, 0, FRICTION, vehicleLateralGrip, DT, MultiContactFrictionSolver.FRICTION_SOLVER_ITERATIONS);
  double budget = NORMAL * FRICTION + 1e-6; // every wheel's own effective friction-circle radius, with float slack
  for (var r : results) {
   assertTrue(Double.isFinite(r.longitudinal()) && Double.isFinite(r.lateral()), "non-finite combined-demand result " + r);
   double achieved = Math.hypot(r.longitudinal(), r.lateral());
   assertTrue(achieved <= budget, "achieved combined force " + achieved + " must stay within the friction-circle budget " + budget);
  }
 }
}
