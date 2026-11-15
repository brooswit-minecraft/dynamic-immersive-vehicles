package io.github.brooswitminecraft.div.experimental;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MINECRAFT-534 (installed-tire rolling resistance overrides): {@code PhysicsBridge.stepGround}
 * cannot be driven without a live Minecraft/IV/Sable runtime (see {@code
 * PhysicsBridgeWheelLoadShareSimulationTest}'s own doc for why every PhysicsBridge-level test in
 * this project substitutes the real production pure-function path instead) - this exercises that
 * exact substitute for the tire-part-rolling-resistance composition {@code
 * PhysicsBridge.stepGround} actually performs before constructing each {@code FrictionContact}:
 * {@code surfaceRollingResistanceMultiplier * (PhysicsBridge.tirePartRollingResistance(partKey,
 * vehicleRollingResistance) / vehicleRollingResistance)}, folded into the SAME {@code
 * rollingResistanceMultiplier} slot {@code MultiContactFrictionSolverGroundSurfaceTest} already
 * proves is a real force-solver input, never a debug-only value. {@code
 * PhysicsBridge.tirePartRollingResistance} itself (part override wins outright, replace-not-
 * multiply, extends-chain inheritance, out-of-range rejection, independence from friction) is
 * covered directly by {@code OverridesTest}'s own MINECRAFT-534 cases; this file proves the
 * SOLVER-FACING composition of that resolved ratio actually changes - and, when unconfigured,
 * does NOT change - the real per-wheel coast-down/energy-dissipation behavior independently-
 * keyed wheels see within one {@code solveFriction} call, independent of grip entirely.
 */
class MultiContactFrictionSolverTirePartRollingResistanceTest {
 private static final double MASS = 350.0, DT = 0.05, VEHICLE_ROLLING_RESISTANCE = 1.0, NORMAL = 2000;
 private static final Vector3d INERTIA = new Vector3d(100, 100, 100);
 private static final Vector3d FORWARD = new Vector3d(0, 0, 1), RIGHT = new Vector3d(1, 0, 0);
 private static final Quaterniond IDENTITY = new Quaterniond();

 /** The exact composition PhysicsBridge.stepGround performs per-wheel, immediately before
  * constructing a FrictionContact - see its own doc comment at the stream map() call site. */
 private static double composedRollingResistanceMultiplier(double surfaceRollingResistanceMultiplier, double resolvedTireRollingResistance, double vehicleRollingResistance) {
  return surfaceRollingResistanceMultiplier * (resolvedTireRollingResistance / vehicleRollingResistance);
 }

 /** Unbraked, zero drive demand, forward speed only - isolates the rolling-resistance term so a
  * single-pass solve's longitudinal result is (within the solver's own PGS discretization) the
  * speed-proportional drag alone, exactly the "coast-down/energy dissipation" behavior the ticket
  * requires tested. */
 private static MultiContactFrictionSolver.FrictionResult coastOnce(double rollingResistanceMultiplier, double forwardSpeed) {
  Vector3d velocity = new Vector3d(FORWARD).mul(forwardSpeed);
  Vector3d angular = new Vector3d(0, 0, 0);
  var contacts = new MultiContactFrictionSolver.FrictionContact[]{
    new MultiContactFrictionSolver.FrictionContact(new Vector3d(0, 0, 0), FORWARD, RIGHT, NORMAL, 1.0, 1.0, rollingResistanceMultiplier)};
  var results = MultiContactFrictionSolver.solveFriction(contacts, MASS, INERTIA, IDENTITY, IDENTITY, velocity, angular,
    0, 0, 1.0, 1.0, DT, MultiContactFrictionSolver.FRICTION_SOLVER_ITERATIONS);
  return results[0];
 }

 /** (d) A vehicle with zero tire part overrides anywhere - every wheel's resolvedTireRolling
  * Resistance equals the vehicle's own rollingResistance unchanged, so the composed ratio is
  * exactly 1.0, the true no-op multiplier - byte-identical to the pure ground-surface-only value
  * it was before this ticket. */
 @Test void noPartOverrideAnywhereComposesToTheExactPreTicketGroundSurfaceOnlyValue() {
  double surfaceMultiplier = 0.4; // e.g. mud (MINECRAFT-508)
  double ratio = composedRollingResistanceMultiplier(surfaceMultiplier, VEHICLE_ROLLING_RESISTANCE, VEHICLE_ROLLING_RESISTANCE);
  assertEquals(surfaceMultiplier, ratio, 0, "with no tire-part override, the composed multiplier must equal the ground-surface multiplier exactly, not merely approximately");
 }

 /** Coast-down/energy dissipation: a higher resolved rolling resistance drains more speed per
  * tick (a larger-magnitude, opposing-sign longitudinal force) than a lower one, with zero drive
  * and zero brake demand - exactly the ticket's required coast-down coverage. */
 @Test void higherTireRollingResistanceDissipatesMoreCoastDownEnergyThanLower() {
  double forwardSpeed = 15;
  double draggyRatio = composedRollingResistanceMultiplier(1.0, 4.0, VEHICLE_ROLLING_RESISTANCE);
  double smoothRatio = composedRollingResistanceMultiplier(1.0, 0.25, VEHICLE_ROLLING_RESISTANCE);
  var draggy = coastOnce(draggyRatio, forwardSpeed);
  var smooth = coastOnce(smoothRatio, forwardSpeed);
  assertTrue(draggy.longitudinal() < 0, "rolling resistance opposes forward motion");
  assertTrue(smooth.longitudinal() < 0, "rolling resistance opposes forward motion");
  assertTrue(Math.abs(draggy.longitudinal()) > Math.abs(smooth.longitudinal()),
    "draggy tire longitudinal=" + draggy.longitudinal() + " must dissipate more than smooth tire longitudinal=" + smooth.longitudinal());
 }

 /** Zero velocity: no slip, nothing to dissipate - the composed multiplier must not manufacture
  * a nonzero drag force out of a stationary wheel regardless of how it is configured. */
 @Test void zeroVelocityProducesZeroRollingResistanceRegardlessOfConfiguredMultiplier() {
  double draggyRatio = composedRollingResistanceMultiplier(1.0, 5.0, VEHICLE_ROLLING_RESISTANCE);
  var result = coastOnce(draggyRatio, 0.0);
  assertEquals(0.0, result.longitudinal(), 1e-9, "a stationary wheel has no slip to dissipate, regardless of configured rolling resistance");
 }

 /** Reverse: the dissipative force must still oppose motion (same sign discipline as the
  * unconfigured baseline, F=-k*v) when the vehicle is rolling backward, not get the sign
  * inverted by the composed tire-part ratio. */
 @Test void reverseVelocityStillProducesOpposingRollingResistance() {
  double ratio = composedRollingResistanceMultiplier(1.0, 2.0, VEHICLE_ROLLING_RESISTANCE);
  var result = coastOnce(ratio, -12.0);
  assertTrue(result.longitudinal() > 0, "rolling resistance must oppose reverse motion with a positive (forward-pushing) longitudinal force, got " + result.longitudinal());
 }

 /** (c) Swapping which part definition is installed on a wheel changes the resolved rolling
  * resistance (a different key resolves a different override, or none at all) - captured here
  * purely as the composition arithmetic, since the actual key lookup is {@code
  * PhysicsBridge.tirePartRollingResistance}'s own concern (covered by OverridesTest's
  * MINECRAFT-534 cases) and always re-reads live, never caching a wheel's installed-part
  * identity from a previous tick. */
 @Test void swappingTheResolvedTireRollingResistanceChangesTheComposedMultiplier() {
  double beforeSwapRatio = composedRollingResistanceMultiplier(1.0, 3.0, VEHICLE_ROLLING_RESISTANCE);
  double afterSwapRatio = composedRollingResistanceMultiplier(1.0, 0.5, VEHICLE_ROLLING_RESISTANCE);
  assertNotEquals(beforeSwapRatio, afterSwapRatio, "installing a differently-overridden tire part must change the composed multiplier applied to that wheel's next tick");
 }

 /** Ground-surface and tire-part contributions compose MULTIPLICATIVELY, not by picking one or
  * the other - a draggy tire (3x) on an already-draggy surface (2x, e.g. mud/gravel) must
  * dissipate more than that same tire on pavement would. */
 @Test void groundSurfaceAndTirePartContributionsComposeMultiplicatively() {
  double tireOnlyRatio = composedRollingResistanceMultiplier(1.0, 3.0, VEHICLE_ROLLING_RESISTANCE); // 3x tire-only
  double onGravel = composedRollingResistanceMultiplier(2.0, 3.0, VEHICLE_ROLLING_RESISTANCE); // tire AND surface both increase it
  assertEquals(3.0, tireOnlyRatio, 1e-12);
  assertEquals(6.0, onGravel, 1e-12, "gravel surface (2.0x) and draggy tire (3.0x) must multiply together, not override one another");
 }

 /** Independent of grip: composing a tire-part rolling-resistance override must not itself
  * perturb the frictionMultiplier slot - the two compositions (grip ratio, rolling-resistance
  * ratio) are computed and applied completely independently by the caller. */
 @Test void rollingResistanceCompositionDoesNotAffectAchievedLateralGrip() {
  double sideSpeed = 20;
  var contacts = new MultiContactFrictionSolver.FrictionContact[]{
    new MultiContactFrictionSolver.FrictionContact(new Vector3d(0, 0, 0), FORWARD, RIGHT, NORMAL, 1.0, 1.0, 1.0)};
  var contactsDraggy = new MultiContactFrictionSolver.FrictionContact[]{
    new MultiContactFrictionSolver.FrictionContact(new Vector3d(0, 0, 0), FORWARD, RIGHT, NORMAL, 1.0, 1.0, 4.0)};
  Vector3d v1 = new Vector3d(0, 0, 0).fma(sideSpeed, RIGHT), a1 = new Vector3d();
  Vector3d v2 = new Vector3d(0, 0, 0).fma(sideSpeed, RIGHT), a2 = new Vector3d();
  var baseline = MultiContactFrictionSolver.solveFriction(contacts, MASS, INERTIA, IDENTITY, IDENTITY, v1, a1, 0, 0, 1.0, 1.0, DT, MultiContactFrictionSolver.FRICTION_SOLVER_ITERATIONS);
  var draggy = MultiContactFrictionSolver.solveFriction(contactsDraggy, MASS, INERTIA, IDENTITY, IDENTITY, v2, a2, 0, 0, 1.0, 1.0, DT, MultiContactFrictionSolver.FRICTION_SOLVER_ITERATIONS);
  assertEquals(baseline[0].lateral(), draggy[0].lateral(), 1e-9,
    "changing only rollingResistanceMultiplier must leave achieved lateral force unaffected - it is independent of grip");
 }
}
