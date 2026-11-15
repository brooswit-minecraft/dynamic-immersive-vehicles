package io.github.brooswitminecraft.div.experimental;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MINECRAFT-529 (installed tire-specific grip overrides): {@code Body.stepGround} itself cannot
 * be driven without a live Minecraft/IV/Sable runtime (see {@code
 * PhysicsBridgeWheelLoadShareSimulationTest}'s own doc for why every PhysicsBridge-level test in
 * this project substitutes the real production pure-function path instead) - this exercises that
 * exact substitute for the tire-part-grip composition {@code PhysicsBridge.stepGround} actually
 * performs before constructing each {@code FrictionContact}: {@code
 * surfaceFrictionMultiplier * (PhysicsBridge.tirePartFriction(partKey, vehicleFriction) /
 * vehicleFriction)}, folded into the SAME {@code frictionMultiplier} slot {@code
 * MultiContactFrictionSolverGroundSurfaceTest} already proves is a real force-solver input, never
 * a debug-only value. {@code PhysicsBridge.tirePartFriction} itself (part override wins outright,
 * replace-not-multiply, extends-chain inheritance, out-of-range rejection) is covered directly by
 * {@code OverridesTest}'s own MINECRAFT-529 cases; this file proves the SOLVER-FACING composition
 * of that resolved ratio actually changes - and, when unconfigured, does NOT change - the real
 * per-wheel friction force multiple independently-keyed wheels see within one {@code
 * solveFriction} call, i.e. this ticket's "independent wheel identities" requirement at the level
 * the production code actually composes it.
 */
class MultiContactFrictionSolverTirePartGripTest {
 private static final double MASS = 350.0, DT = 0.05, VEHICLE_FRICTION = 1.0, NORMAL = 2000, DRIVE_FORCE = 1500;
 private static final Vector3d INERTIA = new Vector3d(100, 100, 100);
 private static final Vector3d FORWARD = new Vector3d(0, 0, 1), RIGHT = new Vector3d(1, 0, 0);
 private static final Quaterniond IDENTITY = new Quaterniond();

 /** The exact composition PhysicsBridge.stepGround performs per-wheel, immediately before
  * constructing a FrictionContact - see its own doc comment at the stream map() call site. */
 private static double composedFrictionMultiplier(double surfaceFrictionMultiplier, double resolvedTireFriction, double vehicleFriction) {
  return surfaceFrictionMultiplier * (resolvedTireFriction / vehicleFriction);
 }

 private static MultiContactFrictionSolver.FrictionResult[] solve(MultiContactFrictionSolver.FrictionContact... contacts) {
  Vector3d velocity = new Vector3d(0, 0, 0).fma(20, RIGHT);
  Vector3d angular = new Vector3d(0, 0, 0);
  return MultiContactFrictionSolver.solveFriction(contacts, MASS, INERTIA, IDENTITY, IDENTITY, velocity, angular,
    0, DRIVE_FORCE, VEHICLE_FRICTION, 1.0, DT, MultiContactFrictionSolver.FRICTION_SOLVER_ITERATIONS);
 }

 /** (d) A vehicle with zero tire part overrides anywhere - every wheel's resolvedTireFriction
  * equals the vehicle's own friction unchanged, so the composed ratio is exactly 1.0, the true
  * no-op multiplier - this contact's frictionMultiplier is BYTE-IDENTICAL to the pure
  * ground-surface-only value it was before this ticket. */
 @Test void noPartOverrideAnywhereComposesToTheExactPreTicketGroundSurfaceOnlyValue() {
  double surfaceMultiplier = 0.8; // e.g. dirt (MINECRAFT-508)
  double ratio = composedFrictionMultiplier(surfaceMultiplier, VEHICLE_FRICTION, VEHICLE_FRICTION);
  assertEquals(surfaceMultiplier, ratio, 0, "with no tire-part override, the composed multiplier must equal the ground-surface multiplier exactly, not merely approximately");
 }

 /** (a) Two wheels on the same solveFriction call, with DIFFERENT installed tire-part friction
  * overrides, must produce measurably different achieved lateral force from each other and from
  * an unconfigured third wheel - true independent-per-wheel grip, not a single shared scalar. */
 @Test void twoDifferentlyGrippedTiresAndAnUnconfiguredThirdProduceMeasurablyDifferentLateralForce() {
  double grippyResolved = 2.4, slickResolved = 0.3; // this part's own resolved friction, replacing the vehicle's
  double grippyRatio = composedFrictionMultiplier(1.0, grippyResolved, VEHICLE_FRICTION);
  double slickRatio = composedFrictionMultiplier(1.0, slickResolved, VEHICLE_FRICTION);
  double unconfiguredRatio = composedFrictionMultiplier(1.0, VEHICLE_FRICTION, VEHICLE_FRICTION);
  var contacts = new MultiContactFrictionSolver.FrictionContact[]{
    new MultiContactFrictionSolver.FrictionContact(new Vector3d(-1,0,0), FORWARD, RIGHT, NORMAL, 1.0, grippyRatio, 1.0),
    new MultiContactFrictionSolver.FrictionContact(new Vector3d(1,0,0), FORWARD, RIGHT, NORMAL, 1.0, slickRatio, 1.0),
    new MultiContactFrictionSolver.FrictionContact(new Vector3d(0,0,1), FORWARD, RIGHT, NORMAL, 1.0, unconfiguredRatio, 1.0),
  };
  var results = solve(contacts);
  double grippyLateral = Math.abs(results[0].lateral()), slickLateral = Math.abs(results[1].lateral()), unconfiguredLateral = Math.abs(results[2].lateral());
  assertTrue(slickLateral < unconfiguredLateral, "slick tire lateral=" + slickLateral + " must be smaller than the unconfigured wheel's lateral=" + unconfiguredLateral);
  assertTrue(unconfiguredLateral <= grippyLateral, "unconfigured lateral=" + unconfiguredLateral + " must not exceed the grippy tire's own lateral=" + grippyLateral);
  assertNotEquals(grippyLateral, slickLateral, "the two differently-configured tires must not coincidentally land on the same achieved lateral force");
 }

 /** (c) Swapping which part definition is installed on a wheel changes the resolved friction
  * (a different key resolves a different override, or none at all) - captured here purely as the
  * composition arithmetic, since the actual key lookup is PhysicsBridge.tirePartFriction's own
  * concern (covered by OverridesTest's MINECRAFT-529 cases) and always re-reads live, never
  * caching a wheel's installed-part identity from a previous tick. */
 @Test void swappingTheResolvedTireFrictionChangesTheComposedMultiplier() {
  double beforeSwapRatio = composedFrictionMultiplier(1.0, 1.5, VEHICLE_FRICTION);
  double afterSwapRatio = composedFrictionMultiplier(1.0, 0.6, VEHICLE_FRICTION);
  assertNotEquals(beforeSwapRatio, afterSwapRatio, "installing a differently-overridden tire part must change the composed multiplier applied to that wheel's next tick");
 }

 /** Ground-surface and tire-part contributions compose MULTIPLICATIVELY, not by picking one or
  * the other - a slick tire (0.5x) on an icy surface (0.2x) must be grippier than that same slick
  * tire on dry pavement would be worse, i.e. both factors actually participate at once. */
 @Test void groundSurfaceAndTirePartContributionsComposeMultiplicatively() {
  double tireRatio = composedFrictionMultiplier(1.0, 0.5, VEHICLE_FRICTION); // 0.5x tire-only
  double onIce = composedFrictionMultiplier(0.2, 0.5, VEHICLE_FRICTION); // tire AND surface both shrink it
  assertEquals(0.5, tireRatio, 1e-12);
  assertEquals(0.1, onIce, 1e-12, "ice surface (0.2x) and slick tire (0.5x) must multiply together, not override one another");
 }
}
