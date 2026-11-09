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

 /**
  * Fine climb/descent trim around the neutral-buoyancy baseline below, from IV's ballast
  * control input (-1..1, release/intake), scaled as a fraction of the vehicle's own weight so it
  * stays mass/gravity-compatible regardless of a pack's literal {@code ballastVolume} number.
  */
 public static double ballastTrim(double mass, double gravity, double ballastControl) {
  double trimFraction = 0.1;
  return mass * gravity * trimFraction * Math.max(-1, Math.min(1, ballastControl));
 }

 /**
  * Neutral-buoyancy lift for a blimp, gated and scaled the way IV's own ballast system is -
  * not a literal Archimedes volume, and not gated or scaled by {@code gravityFactor}. Pinned IV
  * source (reviewed in PR #5) shows gravity suppression is keyed to whether {@code
  * ballastVolume} is configured/active at all (zero/unset means IV applies ordinary gravity to
  * this vehicle, matching a plain Sable body here), not to {@code gravityFactor} - whose own
  * runtime default actually resolves to full gravity (1) when a pack leaves it unset, the
  * opposite of what an earlier revision of this method assumed. {@code ballastVolume} itself is
  * normally {@code emptyMass / 1000} - a small control-authority number, not an SI volume sized
  * to displace the vehicle's mass - so once a nonzero value marks ballast active, this returns
  * the vehicle's full weight as upward lift (net-neutral against gravity) rather than a
  * literal {@code rho * V * g} computed from that number, plus the trim above.
  */
 public static double blimpBuoyancy(double mass, double gravity, double ballastVolume, double ballastControl) {
  if (ballastVolume <= 0) return 0;
  return mass * gravity + ballastTrim(mass, gravity, ballastControl);
 }
}
