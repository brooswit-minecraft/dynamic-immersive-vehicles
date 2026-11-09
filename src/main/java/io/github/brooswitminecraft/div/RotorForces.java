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
  * flapping range is: the disk cannot be tilted onto its edge. */
 public static double cyclicTilt(double inputRadians) {
  return Math.max(-MAX_CYCLIC_TILT, Math.min(MAX_CYCLIC_TILT, inputRadians));
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
  return Math.sin(tiltRadians) * thrust * rotorRadius;
 }

 /** Yaw (anti-torque/tail-rotor) authority, the rotorcraft analogue of a rudder: like cyclic
  * torque, scales with available thrust and arm, not airspeed - see the class-level
  * simplification note on why this is one lumped abstraction rather than a modeled tail rotor. */
 public static double yawTorque(double rudderRadians, double thrust, double tailArm) {
  return rudderRadians * thrust * tailArm;
 }

 /** Rotor disk radius in meters from IV's own propeller diameter field (inches). */
 public static double rotorRadiusMeters(double diameterInches) {
  return diameterInches * 0.0254 / 2;
 }
}
