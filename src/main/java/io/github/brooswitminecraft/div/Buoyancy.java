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
 /** Propeller thrust (N); a propeller produces no thrust at all unless it is underwater. */
 public static double propellerThrust(double throttleSigned, double propellerForce, boolean propellerSubmerged) {
  return propellerSubmerged ? throttleSigned * propellerForce : 0;
 }
}
