package io.github.brooswitminecraft.div;
import org.joml.Vector3d;

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

 /** Sane configuration ceiling on {@code Overrides.Tuning#rotorAngularDampingCoefficient}
  * (MINECRAFT-537). Unlike {@link #MAX_CYCLIC_LIMIT_DEGREES} or an authority scale, this is NOT
  * load-bearing for safety by itself - {@link #angularDampingTorque}'s own no-reversal clamp
  * already bounds one tick's damping impulse to at most the axis's existing angular momentum
  * REGARDLESS of how large the coefficient is, so no finite coefficient can ever inject energy
  * or destabilize the body. The cap exists only so a config value stays a meaningful dial rather
  * than an arbitrary huge number that does nothing extra (every coefficient at or above the point
  * where a tick's damping is already fully clamped to "stop this axis dead" is indistinguishable
  * from every other coefficient at or above that point) and to keep {@code validate()} rejecting
  * a plainly mistyped value (e.g. a stray extra digit) the same way the other authority-style
  * fields above do. */
 public static final double MAX_ANGULAR_DAMPING_COEFFICIENT = 1000.0;

 /**
  * Bounded, dissipative angular-rate damping torque about ONE body-local rotational axis
  * (MINECRAFT-537) - NOT auto-level, NOT a hover autopilot, and NOT cyclic-input filtering: the
  * only inputs are the body's OWN current angular velocity and inertia about this axis, never the
  * pilot's control input, so this can only ever oppose/reduce rotation already present, never add
  * net rotational energy or substitute for the pilot's actual cyclic/yaw torque (see
  * {@code PhysicsBridge.stepRotor}'s own doc for how this is combined with, not instead of, the
  * existing cyclic/yaw control torques).
  *
  * <p>Modeled as linear (viscous) drag: {@code torque = -coefficient * inertiaAxis *
  * angularVelocityAxis}, scaling with this axis's own moment of inertia the same way linear drag
  * scales with mass - so a light, easily-spun axis is not damped identically to a heavy one for
  * the same coefficient. This is the real-world powered/unpowered drag semantics the ticket asks
  * for: friction/windage drag on a rotor's gearbox and hub opposes shaft rotation whether the
  * engine is driving it or not, and does so proportionally to the rotating assembly's own inertia
  * - it grants no free control torque of its own (zero input angular velocity gives zero torque).
  *
  * <p>Clamped so a single tick's damping impulse ({@code torque * dt}) can never remove more
  * angular momentum about this axis than the axis already has - i.e. it can bring this axis's
  * rotation to exactly zero in one tick but never past zero into the opposite direction
  * ("no-reversal"): flipping the sign of an existing angular velocity is itself a net energy
  * INJECTION (the body would now be spinning the other way under a term whose entire premise is
  * removing energy), which is physically backward for a drag term and is exactly the instability
  * an unbounded proportional damping torque could otherwise produce at a large coefficient or a
  * large/variable {@code dt}. The bound is {@code inertiaAxis * |angularVelocityAxis| / dt},
  * which holds at ANY dt (including the "extreme dt" stability requirement) and ANY coefficient
  * (including {@link #MAX_ANGULAR_DAMPING_COEFFICIENT}) - see this method's own no-reversal unit
  * tests.
  *
  * @param angularVelocityAxis this axis's current angular velocity component, rad/s
  * @param inertiaAxis this axis's own moment of inertia (body-frame diagonal), kg*m^2 - must be
  *                     strictly positive, same contract as every other moment-of-inertia
  *                     consumer in this codebase (e.g. {@code PhysicsBridge.effectiveMassDamper})
  * @param dampingCoefficient per-second viscous drag strength, {@code >= 0}; {@code 0} (the
  *                            documented {@code Overrides.Tuning} FALLBACK) returns exactly 0 -
  *                            the zero/off-parity requirement
  * @param dt the physics tick length this torque will be integrated over (as an impulse,
  *           {@code torque*dt}), consistent with every other torque in {@code PhysicsBridge}
  */
 public static double angularDampingTorque(double angularVelocityAxis, double inertiaAxis, double dampingCoefficient, double dt) {
  if (!(dampingCoefficient > 0) || !(dt > 0) || !(inertiaAxis > 0) || angularVelocityAxis == 0) return 0;
  double unbounded = -dampingCoefficient * inertiaAxis * angularVelocityAxis;
  double maxMagnitude = inertiaAxis * Math.abs(angularVelocityAxis) / dt;
  return Math.max(-maxMagnitude, Math.min(maxMagnitude, unbounded));
 }

 /** Sane configuration ceiling on {@code Overrides.Tuning#rotorAirDragCoefficient}
  * (MINECRAFT-543), the same usability/sanity-cap role {@link #MAX_ANGULAR_DAMPING_COEFFICIENT}
  * plays for the rotational damping coefficient: {@link #translationalDragForce}'s own
  * no-reversal clamp already bounds one tick's drag impulse to at most this body's existing
  * momentum along its velocity direction REGARDLESS of how large the multiplier is, so no finite
  * multiplier can ever inject energy, reverse velocity, or destabilize the body. The cap exists
  * only so a config value stays a meaningful dial and {@code validate()} rejects a plainly
  * mistyped value - the same multiplier-cap shape {@code Overrides.Tuning#parasiticDragMultiplier}
  * already uses (5x the native-derived budget), since {@code rotorAirDragCoefficient} is now
  * exactly that kind of field: a multiplier on a native-geometry-derived aerodynamic force, not a
  * raw drag-strength scalar. */
 public static final double MAX_AIR_DRAG_COEFFICIENT = 5.0;

 /**
  * Bounded, dissipative quadratic (aerodynamic) drag force opposing a rotorcraft's OWN actual
  * linear body velocity (MINECRAFT-543) - the translational analogue of
  * {@link #angularDampingTorque} (MINECRAFT-537): the only input is the body's current velocity
  * (never the pilot's collective/cyclic/yaw input), so this can only ever oppose/reduce existing
  * translational motion, never add net kinetic energy or substitute for rotor thrust.
  * Deliberately independent of {@code Overrides.Tuning#cyclicLimitDegrees}/{@code
  * cyclicAuthorityScale}/{@code yawAuthorityScale} (MINECRAFT-522) and {@code
  * rotorAngularDampingCoefficient} (MINECRAFT-537) - this never touches attitude/yaw authority or
  * angular damping, only the linear force budget added alongside {@code PhysicsBridge.stepRotor}'s
  * existing thrust force.
  *
  * <p>Modeled as the ticket's own requested explicit air-density/area/drag-coefficient
  * formulation, {@code force = -multiplier * 0.5*airDensity*Cd*A * |velocity| * velocity}, built
  * entirely from the SAME pieces {@code PhysicsBridge.stepFlight}/{@link FlightForces} already use
  * for fixed-wing parasitic drag rather than inventing a parallel model: {@code airDensity} is the
  * same sea-level-ISA constant {@code PhysicsBridge.Body.AIR_DENSITY} passes to {@link
  * FlightForces#dynamicPressure}; {@code nativeDragCoefficient} is IV's own {@code
  * motorized.dragCoefficient}, floored via {@link FlightForces#dragCoefficient} exactly the way a
  * no-wing (blimp) vehicle's parasitic drag already is (passing {@code aspectRatio=0} short-
  * circuits that method before its {@code liftCoefficient}/{@code parasiticDragMultiplier}/{@code
  * oswaldEfficiency} parameters are ever read, so {@code 0}/{@code 1.0}/{@code 1.0} below are
  * inert placeholders, not a second drag model); {@code referenceArea} is IV's own {@code
  * motorized.wingArea>0 ? motorized.wingArea : motorized.crossSectionalArea} - a rotorcraft has no
  * wing, so this is the SAME native {@code crossSectionalArea} geometry a blimp's own parasitic
  * drag already falls back to, satisfying the ticket's "existing applicable native geometry"
  * requirement directly rather than fabricating a new per-vehicle field; and {@link
  * FlightForces#aeroForce} computes the exact {@code coefficient*area*dynamicPressure} =
  * {@code Cd*A*0.5*airDensity*v^2} magnitude. {@code multiplier} ({@code
  * Overrides.Tuning#rotorAirDragCoefficient}) is the one NEW, operator-configurable dial this
  * ticket adds: {@code 0} (the documented FALLBACK) is a true no-op leaving every unconfigured
  * rotorcraft's force byte-identical to before this ticket - the "default off" requirement - while
  * a positive value scales this native-geometry-derived force up or down, the same "multiplier
  * against an existing calibrated/native number" shape {@code parasiticDragMultiplier}/{@code
  * suspensionSpringMultiplier} already established elsewhere in this class.
  *
  * <p>Clamped so a single tick's drag impulse ({@code |force|*dt}) can never remove more linear
  * momentum along the velocity direction than the body already has - i.e. it can bring the body
  * to exactly zero velocity in one tick but never past zero into the reverse direction
  * ("no-reversal"), the exact translational counterpart of {@link #angularDampingTorque}'s own
  * no-reversal bound: reversing existing velocity under a term whose entire premise is removing
  * kinetic energy would itself be a net energy INJECTION, which is physically backward for a drag
  * term and is exactly the instability an unbounded proportional drag force could otherwise
  * produce at a large multiplier/area/dragCoefficient or a large/variable {@code dt}. The bound is
  * {@code mass * |velocity| / dt}, which holds at ANY dt (including the "extreme dt" stability
  * requirement) and ANY multiplier (including {@link #MAX_AIR_DRAG_COEFFICIENT}) - see this
  * method's own no-reversal unit tests.
  *
  * @param velocity this body's current linear velocity, WORLD frame (m/s) - orientation-
  *                 independent by construction: real translational air drag opposes actual
  *                 motion through the air regardless of the body's attitude, so no rotated-body/
  *                 frame transform is needed here (unlike {@code RotorForces#cyclicTorque}'s
  *                 body-local axes), satisfying the ticket's "rotated body/frame" acceptance case
  *                 trivially - the result is identical for any orientation given the same
  *                 world-frame velocity
  * @param mass this body's own mass, kg - must be strictly positive
  * @param airDensity air density, kg/m^3 ({@code PhysicsBridge.Body.AIR_DENSITY}, sea-level ISA) -
  *                    must be strictly positive
  * @param nativeDragCoefficient IV's own raw {@code motorized.dragCoefficient} (unfloored) - this
  *                               method floors it itself via {@link FlightForces#dragCoefficient},
  *                               the exact same floor a blimp's own parasitic drag gets
  * @param referenceArea IV's own native reference area ({@code motorized.wingArea>0 ?
  *                        motorized.wingArea : motorized.crossSectionalArea}), m^2 - must be
  *                        strictly positive, or this returns an exact-zero vector (a vehicle whose
  *                        native geometry claims no frontal area gets no drag, regardless of
  *                        {@code multiplier})
  * @param multiplier the resolved, bounded {@code Overrides.Tuning#rotorAirDragCoefficient},
  *                    {@code >= 0}; {@code 0} (the documented FALLBACK) returns an exact-zero
  *                    vector - the zero-velocity/off-parity requirement
  * @param dt the physics tick length this force will be integrated over (as an impulse, {@code
  *           force*dt}), consistent with every other force in {@code PhysicsBridge}
  */
 public static Vector3d translationalDragForce(Vector3d velocity, double mass, double airDensity,
     double nativeDragCoefficient, double referenceArea, double multiplier, double dt) {
  double speed = velocity.length();
  if (!(multiplier > 0) || !(dt > 0) || !(mass > 0) || !(airDensity > 0) || !(referenceArea > 0) || speed == 0) return new Vector3d(0, 0, 0);
  double dragCoefficient = FlightForces.dragCoefficient(0, nativeDragCoefficient, 0, 1.0, 1.0);
  double dynamicPressure = FlightForces.dynamicPressure(airDensity, speed);
  double unboundedMagnitude = multiplier * FlightForces.aeroForce(dragCoefficient, referenceArea, dynamicPressure);
  double maxMagnitude = mass * speed / dt;
  double magnitude = Math.min(unboundedMagnitude, maxMagnitude);
  return new Vector3d(velocity).mul(-magnitude / speed);
 }
}
