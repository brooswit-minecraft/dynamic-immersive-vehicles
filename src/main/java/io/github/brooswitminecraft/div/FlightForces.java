package io.github.brooswitminecraft.div;

/**
 * SI units throughout. Pure aerodynamic/buoyant force model for aircraft and blimp flight, kept
 * in the same focused, unit-testable style as {@link TireForces}: a thin-airfoil lift slope with
 * a hard stall cutoff, parasitic+induced drag, linear control-surface deflection authority, a
 * throttle-linear thrust model, and simple displaced-air buoyancy for blimps. This is a
 * deliberately simplified replacement for IV's own flight model fidelity (no post-stall
 * departure/spin behavior, no advance-ratio propeller falloff, no altitude/weather air-density
 * variation) - the same tradeoff the ground solver already made against a full tire/suspension
 * simulation. Operates on plain numbers only, so it needs no live entity, world, or Sable
 * runtime to test.
 */
public final class FlightForces {
 private FlightForces() {}
 private static final double STALL_ANGLE = Math.toRadians(15);
 /** Thin-airfoil lift slope, per radian of angle of attack or control-surface deflection. */
 private static final double LIFT_SLOPE = 2 * Math.PI;
 private static final double GRAVITY = 9.81;

 /** Dynamic pressure q = 1/2 * rho * v^2, the common scale factor for every aerodynamic force. */
 public static double dynamicPressure(double airDensity, double speed) {
  return 0.5 * airDensity * speed * speed;
 }

 /**
  * Lift coefficient from angle of attack, clamped at a hard stall rather than following the real
  * post-stall coefficient collapse - this model targets level and moderately banked/climbing
  * flight, not spins or departed flight.
  */
 public static double liftCoefficient(double angleOfAttackRadians) {
  double clamped = Math.max(-STALL_ANGLE, Math.min(STALL_ANGLE, angleOfAttackRadians));
  return LIFT_SLOPE * clamped;
 }

 /**
  * Parasitic plus induced drag coefficient. {@code aspectRatio <= 0} (no wing, e.g. a blimp hull)
  * skips the induced term entirely and returns just the parasitic coefficient.
  */
 public static double dragCoefficient(double liftCoefficient, double parasiticDrag, double aspectRatio) {
  if (aspectRatio <= 0) return parasiticDrag;
  double oswaldEfficiency = 0.8;
  double induced = (liftCoefficient * liftCoefficient) / (Math.PI * aspectRatio * oswaldEfficiency);
  return parasiticDrag + induced;
 }

 /** Aerodynamic force magnitude for one coefficient/area pair at this dynamic pressure. */
 public static double aeroForce(double coefficient, double area, double dynamicPressure) {
  return coefficient * area * dynamicPressure;
 }

 /**
  * A control surface's own incremental force from its deflection: the same linear per-radian
  * lift slope as the wing, scaled by the surface's own JSON area. Shared by aileron roll torque,
  * elevator pitch torque, and rudder yaw torque.
  */
 public static double controlSurfaceForce(double deflectionRadians, double area, double dynamicPressure) {
  return LIFT_SLOPE * deflectionRadians * area * dynamicPressure;
 }

 /** Throttle-linear thrust; this model does not account for airspeed/advance-ratio falloff. */
 public static double thrust(double throttle, double maxThrust) {
  return throttle * maxThrust;
 }

 /** Buoyant lift from a displaced-air ballast volume (blimps): rho_air * V * g. */
 public static double buoyancy(double airDensity, double ballastVolume) {
  return airDensity * ballastVolume * GRAVITY;
 }
}
