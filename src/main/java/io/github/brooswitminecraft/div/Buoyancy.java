package io.github.brooswitminecraft.div;
/**
 * SI units throughout. Pure force math for float-only (pontoon/skid-with-canFloat) hulls - a
 * different physics domain than TireForces' raycast spring/damper ground solver, so it needs
 * its own model: submersion depth against a local water surface, Archimedes buoyant force,
 * quadratic water drag, and propeller thrust that only exists while the propeller itself is
 * underwater. Operates on plain numbers, so it needs no live entity, world, or Sable runtime.
 */
public final class Buoyancy {
 private Buoyancy() {}
 public static final double WATER_DENSITY_KG_M3 = 1000.0;
 public static final double GRAVITY = 9.81;
 /** Fraction (0..1) of a vertical span [bottom,top] lying at or below the water surface. */
 public static double submergedFraction(double bottom, double top, double waterSurfaceY) {
  if (top <= bottom) return 0;
  return Math.max(0, Math.min(1, (waterSurfaceY - bottom) / (top - bottom)));
 }
 /** Upward Archimedes force (N) for a hull of the given full displacement volume (m^3). */
 public static double buoyantForce(double submergedFraction, double hullVolume) {
  return submergedFraction * hullVolume * WATER_DENSITY_KG_M3 * GRAVITY;
 }
 /** Quadratic water drag magnitude (N), opposing motion, scaled by how submerged the hull is. */
 public static double drag(double speed, double submergedFraction, double dragCoefficient) {
  return submergedFraction * dragCoefficient * speed * speed;
 }
 /** Propeller thrust (N); a propeller produces no thrust at all unless it is both underwater
  * and itself active (IV's own isActiveVar - not destroyed/removed) - an inactive or damaged
  * propeller stays silent regardless of throttle or submersion. */
 public static double propellerThrust(double throttleSigned, double propellerForce, boolean propellerSubmerged, boolean propellerActive) {
  return (propellerSubmerged && propellerActive) ? throttleSigned * propellerForce : 0;
 }
 /** Splits the shared propeller-force budget evenly across only the propellers actually
  * operational this tick (driven by a running, linked engine, active, and turning) - a
  * non-operational propeller must not dilute the share a real one gets just by existing on
  * the chassis. Zero if none are operational. */
 public static double perOperationalPropellerForce(double propellerForceBudget, int operationalCount) {
  return operationalCount <= 0 ? 0 : propellerForceBudget / operationalCount;
 }
 /** The exact per-propeller selection test {@code BuoyantForces} applies before a propeller
  * counts toward {@link #perOperationalPropellerForce}'s denominator at all: linked to a
  * currently-running engine ({@code linkedToRunningEngine} - from
  * {@code PartEngineAccessor.linkedPropellers}, not reachable through any public API), not
  * destroyed/removed ({@code active} - IV's own {@code isActiveVar}), and actually turning
  * ({@code currentRpm} away from zero - from {@code PartPropellerAccessor}, also not public).
  * An active propeller that is simply not linked to any running engine (so no engine drives
  * it at all) is excluded here exactly like an inactive or stalled one - all three conditions
  * are checked independently, so no single one can substitute for the others. */
 public static boolean isPropellerOperational(boolean linkedToRunningEngine, boolean active, double currentRpm) {
  return linkedToRunningEngine && active && Math.abs(currentRpm) > 1e-3;
 }
}
