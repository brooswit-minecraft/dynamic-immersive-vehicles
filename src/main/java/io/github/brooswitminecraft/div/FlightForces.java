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
 /**
  * MINECRAFT-521: these three were previously hardcoded here (stall cap, lift slope, control-
  * surface slope); they are now {@link Overrides.Tuning} fields instead, so every caller of
  * {@link #liftCoefficient} and {@link #controlSurfaceForce} must pass a resolved value rather
  * than this class picking one implicitly. These constants ARE that resolved value's own
  * documented FALLBACK (byte-identical to the old hardcoded behavior for an unconfigured
  * vehicle) - {@link Overrides}'s FALLBACK block reuses them directly rather than re-stating the
  * same numbers a second time.
  */
 public static final double DEFAULT_STALL_ANGLE_DEGREES = 15.0;
 /** Thin-airfoil lift slope, per radian of angle of attack. */
 public static final double DEFAULT_LIFT_SLOPE = 2 * Math.PI;
 /** Per-radian control-surface deflection authority - previously the same constant as the wing's
  * own lift slope; now independently tunable (see {@link #controlSurfaceForce}). */
 public static final double DEFAULT_CONTROL_SURFACE_SLOPE = 2 * Math.PI;
 /**
  * MINECRAFT-536: the induced-drag term's Oswald efficiency factor was previously hardcoded
  * here, inside {@link #dragCoefficient}; it is now a caller-supplied {@link
  * Overrides.Tuning#oswaldEfficiency} instead, the same "class picks no implicit default of its
  * own" shape MINECRAFT-521 already established for stallAngleDegrees/liftSlope/
  * controlSurfaceSlope. This constant IS that field's own documented FALLBACK - byte-identical
  * to the old hardcoded 0.8 for an unconfigured vehicle.
  */
 public static final double DEFAULT_OSWALD_EFFICIENCY = 0.8;
 /**
  * Sane configuration ceiling on {@code Overrides.Tuning#fixedWingAngularDampingCoefficient}
  * (MINECRAFT-542) - mirrors {@link RotorForces#MAX_ANGULAR_DAMPING_COEFFICIENT} exactly (same
  * reasoning: {@link PhysicsBridge#rotorAngularDampingTorque}'s own no-reversal clamp, reused
  * verbatim here via {@link RotorForces#angularDampingTorque}, already bounds one tick's damping
  * impulse to at most the axis's existing angular momentum regardless of how large the
  * coefficient is, so this cap is a usability ceiling, not a load-bearing safety bound). A
  * SEPARATE constant, not a shared reference to {@link RotorForces#MAX_ANGULAR_DAMPING_COEFFICIENT} -
  * this ticket reuses M537's dissipative-drag formula and no-reversal clamp, never its rotorcraft
  * tuning field/bound, so a fixed-wing pack author's configured ceiling can never be confused
  * with (or silently coupled to) a rotorcraft's.
  */
 public static final double MAX_FIXED_WING_ANGULAR_DAMPING_COEFFICIENT = 1000.0;
 private static final double GRAVITY = 9.81;

 /** Dynamic pressure q = 1/2 * rho * v^2, the common scale factor for every aerodynamic force. */
 public static double dynamicPressure(double airDensity, double speed) {
  return 0.5 * airDensity * speed * speed;
 }

 /**
  * Lift coefficient from angle of attack, clamped at a hard stall rather than following the real
  * post-stall coefficient collapse - this model targets level and moderately banked/climbing
  * flight, not spins or departed flight. {@code stallAngleDegrees} and {@code liftSlope} are the
  * resolved, bounded {@link Overrides.Tuning#stallAngleDegrees}/{@link Overrides.Tuning#liftSlope}
  * for this vehicle (see {@link #DEFAULT_STALL_ANGLE_DEGREES}/{@link #DEFAULT_LIFT_SLOPE} for the
  * unconfigured fallback) - this method itself has no implicit default of its own.
  */
 public static double liftCoefficient(double angleOfAttackRadians, double stallAngleDegrees, double liftSlope) {
  double stallAngleRadians = Math.toRadians(stallAngleDegrees);
  double clamped = Math.max(-stallAngleRadians, Math.min(stallAngleRadians, angleOfAttackRadians));
  return liftSlope * clamped;
 }

 /**
  * Parasitic plus induced drag coefficient. {@code nativeDragCoefficient} is IV's own raw
  * {@code motorized.dragCoefficient} - this method applies the existing {@code Math.max(0.02,
  * ...)} floor itself (previously done by the caller; moved here, MINECRAFT-536 review, so the
  * floor-plus-multiplier-plus-aspect-gating logic below is covered by a plain unit test rather
  * than only by {@code PhysicsBridge.stepFlight}'s own untestable live call site).
  *
  * <p>{@code aspectRatio <= 0} (no wing, e.g. a blimp hull) returns just the floored native
  * value, UNSCALED by {@code parasiticDragMultiplier} - this ticket's fixed-wing-only scope
  * requires preserving a non-fixed-wing/blimp vehicle's parasitic drag exactly, so the multiplier
  * (and {@code oswaldEfficiency}, which has nothing to act on either in this branch) is simply
  * never consulted when there is no wing, rather than risking a spurious divide-by-zero or an
  * out-of-scope behavior change. Only a positive-aspect-ratio fixed wing gets the multiplier
  * applied (to the floored value) and the induced term added. {@code parasiticDragMultiplier}/
  * {@code oswaldEfficiency} are the resolved, bounded {@link Overrides.Tuning#parasiticDragMultiplier}/
  * {@link Overrides.Tuning#oswaldEfficiency} (see their own FALLBACK docs for the unconfigured
  * values) - this method itself has no implicit default of its own for either.
  */
 public static double dragCoefficient(double liftCoefficient, double nativeDragCoefficient, double aspectRatio, double parasiticDragMultiplier, double oswaldEfficiency) {
  double flooredParasiticDrag = Math.max(0.02, nativeDragCoefficient);
  if (aspectRatio <= 0) return flooredParasiticDrag;
  double parasiticDrag = flooredParasiticDrag * parasiticDragMultiplier;
  double induced = (liftCoefficient * liftCoefficient) / (Math.PI * aspectRatio * oswaldEfficiency);
  return parasiticDrag + induced;
 }

 /**
  * MINECRAFT-542: gates {@code Overrides.Tuning#fixedWingAngularDampingCoefficient} the "fixed-
  * wing only, never a blimp/no-wing vehicle" way {@link #dragCoefficient}'s own {@code
  * parasiticDragMultiplier}/{@code oswaldEfficiency} are already gated (MINECRAFT-536, review
  * correction) - but on BOTH the no-wing case ({@code aspectRatio<=0}) AND the authoritative
  * {@code motorized.isBlimp} classification (review correction: a blimp definition can itself
  * carry a positive {@code wingArea}/{@code wingSpan} - e.g. stabilizer fins, or simply a copied/
  * mistaken config - which would otherwise give a positive aspect ratio despite the vehicle being
  * a blimp), returning exactly 0 regardless of the configured coefficient in either case, so
  * {@code PhysicsBridge.stepFlight}'s resulting {@link PhysicsBridge#rotorAngularDampingTorque}
  * call (and therefore the blimp's angular motion) stays completely unaffected by this field no
  * matter how it or its wing geometry are configured, matching the ticket's "fixed-wing only,
  * preserve blimps" scope exactly rather than merely still-damped-by-something. Only a non-blimp,
  * positive-aspect-ratio fixed wing gets the configured coefficient passed through unchanged.
  * Extracted as its own pure, unit-testable function (same reasoning {@code dragCoefficient}'s
  * own gating was moved here for, MINECRAFT-536 review) rather than an inline ternary at
  * {@code stepFlight}'s own untestable live call site.
  */
 public static double gatedFixedWingAngularDampingCoefficient(double aspectRatio, boolean isBlimp, double fixedWingAngularDampingCoefficient) {
  return (aspectRatio > 0 && !isBlimp) ? fixedWingAngularDampingCoefficient : 0.0;
 }

 /** Aerodynamic force magnitude for one coefficient/area pair at this dynamic pressure. */
 public static double aeroForce(double coefficient, double area, double dynamicPressure) {
  return coefficient * area * dynamicPressure;
 }

 /**
  * A control surface's own incremental force from its deflection: a linear per-radian authority
  * slope - {@code controlSurfaceSlope}, the resolved {@link Overrides.Tuning#controlSurfaceSlope}
  * (see {@link #DEFAULT_CONTROL_SURFACE_SLOPE} for the unconfigured fallback), independently
  * tunable from the wing's own {@link #liftCoefficient} slope - scaled by the surface's own JSON
  * area. Shared by aileron roll torque, elevator pitch torque, and rudder yaw torque.
  */
 public static double controlSurfaceForce(double deflectionRadians, double area, double dynamicPressure, double controlSurfaceSlope) {
  return controlSurfaceForce(deflectionRadians, area, dynamicPressure, controlSurfaceSlope, 1.0);
 }

 /**
  * {@link #controlSurfaceForce(double, double, double, double)} with an explicit, independent
  * per-axis operator-configured authority multiplier ({@code Overrides.Tuning#
  * aileronAuthorityScale}/{@code elevatorAuthorityScale}/{@code rudderAuthorityScale},
  * MINECRAFT-541) applied on top of the unchanged {@code controlSurfaceSlope}/area/
  * dynamic-pressure physics - same contract as {@link RotorForces#cyclicTorque(double, double,
  * double, double)}/{@link RotorForces#yawTorque(double, double, double, double)}: {@code 1.0}
  * is a pure no-op reproducing the exact prior shared-{@code controlSurfaceSlope}-only force,
  * and the caller passes each axis its own independent scale, so changing one axis's authority
  * can never affect another axis, the shared slope, or this surface's own native area - this
  * method does not duplicate or re-derive any of those, only multiplies the final result.
  */
 public static double controlSurfaceForce(double deflectionRadians, double area, double dynamicPressure, double controlSurfaceSlope, double authorityScale) {
  return controlSurfaceSlope * deflectionRadians * area * dynamicPressure * authorityScale;
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
