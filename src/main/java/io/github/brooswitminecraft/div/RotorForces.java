package io.github.brooswitminecraft.div;

/**
 * SI units throughout. Pure force model for rotorcraft (helicopters), kept in the same focused,
 * unit-testable style as {@link FlightForces} and {@link TireForces}: a rotor has no wing, so it
 * cannot use {@link FlightForces}'s angle-of-attack lift model - it generates vertical thrust
 * from collective blade pitch (commanded by throttle) and steers that thrust vector with cyclic
 * tilt (commanded by elevator/aileron input) instead of banking into the airflow with control
 * surfaces. Unlike a fixed-wing control surface, a rotor's attitude and yaw authority come from
 * the powered rotor disk itself, not from airflow over a passive surface, so they scale with
 * available thrust rather than airspeed/dynamic pressure - which is exactly why a helicopter has
 * full control hovering at zero airspeed, unlike a stalled airplane. Deliberately simplified
 * relative to IV's own rotor model: no translational-lift efficiency gain in forward flight, no
 * retreating-blade stall, no autorotation/engine-out descent, no ground effect, and every
 * installed rotor propeller is treated as one lumped main lift rotor rather than modeling an
 * individual tail rotor's own thrust and mount orientation - yaw authority below is instead a
 * powered anti-torque abstraction proportional to rudder input. Operates on plain numbers only,
 * so it needs no live entity, world, or Sable runtime to test.
 */
public final class RotorForces {
 private RotorForces() {}
 private static final double MAX_CYCLIC_TILT = Math.toRadians(20);
 /** Hard safety cap on a configured cyclic limit (MINECRAFT-522): a real rotor disk's blade
  * flapping range cannot be tilted onto its edge no matter how an operator configures it, so
  * {@link Overrides.Tuning#cyclicLimitDegrees} is range-checked against this at config load/
  * reload time - see {@link Overrides.Tuning#validate}. */
 public static final double MAX_CYCLIC_LIMIT_DEGREES = 45.0;

 /**
  * Collective pitch authority from throttle, matching IV's own rotor pitch mapping
  * ({@code PartPropeller.update}: {@code throttle*1.35-0.35}, clamped to the 0..1 lift-authority
  * range) - a rotor's lift comes from blade pitch/RPM the pilot commands directly via throttle,
  * not from airspeed over a fixed wing, so there is a low-throttle deadband with zero lift
  * rather than {@link FlightForces#thrust} style linear-from-zero throttle response.
  */
 public static double collective(double throttle) {
  return Math.max(0, Math.min(1, throttle * 1.35 - 0.35));
 }

 /** Vertical thrust from collective pitch, scaled by the shared per-car max-thrust knob - the
  * rotor analogue of {@link FlightForces#thrust}, but driven by collective, not raw throttle. */
 public static double lift(double collective, double maxThrust) {
  return collective * maxThrust;
 }

 /** Cyclic tilt angle (forward/back or side-to-side), clamped the way a real rotor's blade
  * flapping range is: the disk cannot be tilted onto its edge. Uses the fixed 20-degree default
  * clamp - see {@link #cyclicTilt(double, double)} for the configurable-limit overload
  * (MINECRAFT-522). */
 public static double cyclicTilt(double inputRadians) {
  return cyclicTilt(inputRadians, MAX_CYCLIC_TILT);
 }

 /** {@link #cyclicTilt(double)} with an explicit, operator-configured clamp
  * ({@code Overrides.Tuning#cyclicLimitDegrees}, MINECRAFT-522) instead of the fixed 20-degree
  * default - {@code maxTiltRadians} itself is expected to already be validated/clamped to
  * {@link #MAX_CYCLIC_LIMIT_DEGREES} by {@link Overrides.Tuning#validate}, so this does not
  * re-check it. */
 public static double cyclicTilt(double inputRadians, double maxTiltRadians) {
  return Math.max(-maxTiltRadians, Math.min(maxTiltRadians, inputRadians));
 }

 /** The vertical component of rotor thrust once the disk is tilted by cyclic input - tilting
  * the disk to move forward/sideways necessarily steals from vertical lift, the mechanism
  * behind a real helicopter's hover-to-forward-flight transition. */
 public static double verticalComponent(double thrust, double tiltMagnitudeRadians) {
  return thrust * Math.cos(tiltMagnitudeRadians);
 }

 /** The horizontal component of rotor thrust along one cyclic axis (forward or lateral) from
  * tilting the disk by that axis's own cyclic angle. */
 public static double horizontalComponent(double thrust, double tiltAngleRadians) {
  return thrust * Math.sin(tiltAngleRadians);
 }

 /**
  * Attitude torque from cyclic input. Unlike a fixed-wing control surface
  * ({@link FlightForces#controlSurfaceForce}), a rotor's authority to bank/pitch the fuselage
  * comes from tilting the powered rotor disk, not from airflow over a passive surface - it
  * scales with available thrust and rotor disk radius, not with dynamic pressure.
  */
 public static double cyclicTorque(double tiltRadians, double thrust, double rotorRadius) {
  return cyclicTorque(tiltRadians, thrust, rotorRadius, 1.0);
 }

 /** {@link #cyclicTorque(double, double, double)} with an explicit operator-configured
  * authority multiplier ({@code Overrides.Tuning#cyclicAuthorityScale}, MINECRAFT-522) applied
  * on top of the unchanged thrust/radius physics - 1.0 reproduces the exact prior behavior. Still
  * respects thrust availability (zero thrust still gives zero torque regardless of scale) and
  * grants no unpowered free torque - the scale only reshapes how much of the already-available,
  * thrust-derived authority reaches the fuselage. */
 public static double cyclicTorque(double tiltRadians, double thrust, double rotorRadius, double authorityScale) {
  return Math.sin(tiltRadians) * thrust * rotorRadius * authorityScale;
 }

 /** Yaw (anti-torque/tail-rotor) authority, the rotorcraft analogue of a rudder: like cyclic
  * torque, scales with available thrust and arm, not airspeed - see the class-level
  * simplification note on why this is one lumped abstraction rather than a modeled tail rotor. */
 public static double yawTorque(double rudderRadians, double thrust, double tailArm) {
  return yawTorque(rudderRadians, thrust, tailArm, 1.0);
 }

 /** {@link #yawTorque(double, double, double)} with an explicit operator-configured authority
  * multiplier ({@code Overrides.Tuning#yawAuthorityScale}, MINECRAFT-522) - same contract as
  * {@link #cyclicTorque(double, double, double, double)}: 1.0 is a no-op, still gated by thrust,
  * no free unpowered torque. */
 public static double yawTorque(double rudderRadians, double thrust, double tailArm, double authorityScale) {
  return rudderRadians * thrust * tailArm * authorityScale;
 }

 /** Rotor disk radius in meters from IV's own propeller diameter field (inches). */
 public static double rotorRadiusMeters(double diameterInches) {
  return diameterInches * 0.0254 / 2;
 }
}
