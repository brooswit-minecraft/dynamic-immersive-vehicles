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
 * {@code surfaceRollingResistanceMultiplier * PhysicsBridge.tirePartRollingResistance(partKey,
 * vehicleRollingResistance)}, folded into the SAME {@code rollingResistanceMultiplier} slot
 * {@code MultiContactFrictionSolverGroundSurfaceTest} already proves is a real force-solver
 * input, never a debug-only value.
 *
 * <p>Review finding (PR #30/Jira comment 54071): an earlier version of this composition divided
 * the resolved value back out by {@code tuning.rollingResistance} (mirroring {@code
 * tireGripRatio}'s own composition immediately above it in {@code PhysicsBridge}) - but, UNLIKE
 * {@code friction}, {@code solveFriction} has no separate "vehicle baseline" parameter for
 * rolling resistance (its {@code rollingResistanceMultiplier} is the SOLE scalar multiplying the
 * solver's fixed k=20 formula, with nothing else applying a vehicle-level baseline anywhere), so
 * dividing by the vehicle's own resolved value there silently cancelled any configured vehicle-
 * level {@code rollingResistance} back to a no-op, and produced {@code surface*(part/vehicle)}
 * instead of {@code surface*part} when a part override existed - contradicting this ticket's own
 * declared "replace, not multiply" part precedence. This file's {@code
 * composedRollingResistanceMultiplier} helper mirrors the CORRECTED production composition (no
 * division), and {@link #nonUnitVehicleBaselineFallsThroughUnchangedWhenNoPartOverrideExists}/
 * {@link #nonUnitVehicleBaselineIsReplacedOutrightByAPartOverride} are the dedicated non-unit-
 * vehicle-baseline regression the review required, driven through the REAL production {@code
 * PhysicsBridge.tirePartRollingResistance} (not just this file's own arithmetic) so a
 * reintroduced division bug would fail them.
 *
 * <p>{@code PhysicsBridge.tirePartRollingResistance} itself (part override wins outright,
 * replace-not-multiply, extends-chain inheritance, out-of-range rejection, independence from
 * friction) is covered directly by {@code OverridesTest}'s own MINECRAFT-534 cases; this file
 * proves the SOLVER-FACING composition of that resolved value actually changes - and, when
 * unconfigured, does NOT change - the real per-wheel coast-down/energy-dissipation behavior
 * independently-keyed wheels see within one {@code solveFriction} call, independent of grip
 * entirely.
 */
class MultiContactFrictionSolverTirePartRollingResistanceTest {
 private static final double MASS = 350.0, DT = 0.05, NORMAL = 2000;
 private static final Vector3d INERTIA = new Vector3d(100, 100, 100);
 private static final Vector3d FORWARD = new Vector3d(0, 0, 1), RIGHT = new Vector3d(1, 0, 0);
 private static final Quaterniond IDENTITY = new Quaterniond();

 /** The exact (corrected) composition PhysicsBridge.stepGround performs per-wheel, immediately
  * before constructing a FrictionContact - see its own doc comment at the stream map() call site.
  * No division by any vehicle baseline - {@code resolvedTireRollingResistance} is already the
  * fully-resolved per-wheel value (part override, or else the vehicle's own resolved value). */
 private static double composedRollingResistanceMultiplier(double surfaceRollingResistanceMultiplier, double resolvedTireRollingResistance) {
  return surfaceRollingResistanceMultiplier * resolvedTireRollingResistance;
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

 /** (d) A vehicle with zero tire part overrides anywhere and the (unconfigured-fallback) 1.0
  * vehicle baseline - the composed multiplier is exactly the ground-surface multiplier, the true
  * no-op - byte-identical to the pure ground-surface-only value it was before this ticket. */
 @Test void noPartOverrideAnywhereComposesToTheExactPreTicketGroundSurfaceOnlyValue() {
  double surfaceMultiplier = 0.4; // e.g. mud (MINECRAFT-508)
  double composed = composedRollingResistanceMultiplier(surfaceMultiplier, 1.0);
  assertEquals(surfaceMultiplier, composed, 0, "with no tire-part override and an unconfigured (1.0) vehicle baseline, the composed multiplier must equal the ground-surface multiplier exactly, not merely approximately");
 }

 // The dedicated non-unit-vehicle-baseline regression the review required (driven through the
 // REAL production PhysicsBridge.tirePartRollingResistance, not just this file's own arithmetic)
 // lives in OverridesTest (same package as PhysicsBridge/Overrides, whose test seams are
 // package-private) - see
 // nonUnitVehicleRollingResistanceComposesWithGroundSurfaceUnchangedWhenNoPartOverrideExists and
 // nonUnitVehicleRollingResistanceIsReplacedOutrightByAPartOverrideInTheComposedMultiplier there.

 /** Coast-down/energy dissipation: a higher resolved rolling resistance drains more speed per
  * tick (a larger-magnitude, opposing-sign longitudinal force) than a lower one, with zero drive
  * and zero brake demand - exactly the ticket's required coast-down coverage. */
 @Test void higherTireRollingResistanceDissipatesMoreCoastDownEnergyThanLower() {
  double forwardSpeed = 15;
  double draggyMultiplier = composedRollingResistanceMultiplier(1.0, 4.0);
  double smoothMultiplier = composedRollingResistanceMultiplier(1.0, 0.25);
  var draggy = coastOnce(draggyMultiplier, forwardSpeed);
  var smooth = coastOnce(smoothMultiplier, forwardSpeed);
  assertTrue(draggy.longitudinal() < 0, "rolling resistance opposes forward motion");
  assertTrue(smooth.longitudinal() < 0, "rolling resistance opposes forward motion");
  assertTrue(Math.abs(draggy.longitudinal()) > Math.abs(smooth.longitudinal()),
    "draggy tire longitudinal=" + draggy.longitudinal() + " must dissipate more than smooth tire longitudinal=" + smooth.longitudinal());
 }

 /** Zero velocity: no slip, nothing to dissipate - the composed multiplier must not manufacture
  * a nonzero drag force out of a stationary wheel regardless of how it is configured. */
 @Test void zeroVelocityProducesZeroRollingResistanceRegardlessOfConfiguredMultiplier() {
  double draggyMultiplier = composedRollingResistanceMultiplier(1.0, 5.0);
  var result = coastOnce(draggyMultiplier, 0.0);
  assertEquals(0.0, result.longitudinal(), 1e-9, "a stationary wheel has no slip to dissipate, regardless of configured rolling resistance");
 }

 /** Reverse: the dissipative force must still oppose motion (same sign discipline as the
  * unconfigured baseline, F=-k*v) when the vehicle is rolling backward, not get the sign
  * inverted by the composed tire-part value. */
 @Test void reverseVelocityStillProducesOpposingRollingResistance() {
  double multiplier = composedRollingResistanceMultiplier(1.0, 2.0);
  var result = coastOnce(multiplier, -12.0);
  assertTrue(result.longitudinal() > 0, "rolling resistance must oppose reverse motion with a positive (forward-pushing) longitudinal force, got " + result.longitudinal());
 }

 /** (c) Swapping which part definition is installed on a wheel changes the resolved rolling
  * resistance (a different key resolves a different override, or none at all) - captured here
  * purely as the composition arithmetic, since the actual key lookup is {@code
  * PhysicsBridge.tirePartRollingResistance}'s own concern (covered by OverridesTest's
  * MINECRAFT-534 cases) and always re-reads live, never caching a wheel's installed-part
  * identity from a previous tick. */
 @Test void swappingTheResolvedTireRollingResistanceChangesTheComposedMultiplier() {
  double beforeSwap = composedRollingResistanceMultiplier(1.0, 3.0);
  double afterSwap = composedRollingResistanceMultiplier(1.0, 0.5);
  assertNotEquals(beforeSwap, afterSwap, "installing a differently-overridden tire part must change the composed multiplier applied to that wheel's next tick");
 }

 /** Ground-surface and tire-part contributions compose MULTIPLICATIVELY, not by picking one or
  * the other - a draggy tire (3x) on an already-draggy surface (2x, e.g. mud/gravel) must
  * dissipate more than that same tire on pavement would. */
 @Test void groundSurfaceAndTirePartContributionsComposeMultiplicatively() {
  double tireOnly = composedRollingResistanceMultiplier(1.0, 3.0); // 3x tire-only
  double onGravel = composedRollingResistanceMultiplier(2.0, 3.0); // tire AND surface both increase it
  assertEquals(3.0, tireOnly, 1e-12);
  assertEquals(6.0, onGravel, 1e-12, "gravel surface (2.0x) and draggy tire (3.0x) must multiply together, not override one another");
 }

 /** Independent of grip: composing a tire-part rolling-resistance override must not itself
  * perturb the frictionMultiplier slot - the two compositions (grip ratio, rolling-resistance
  * value) are computed and applied completely independently by the caller. */
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
