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
 /**
  * MINECRAFT-535: directional (body-local) analogue of {@link #drag} - one axis's own signed
  * drag force component (N), opposing that axis's own point-velocity component, scaled by an
  * axis-specific multiplier on top of the existing isotropic {@code dragCoefficient}. The
  * isotropic force this ticket extends is exactly {@code -submergedFraction*dragCoefficient*
  * speed*velocityComponent} for every axis (the vector form {@code -dragMagnitude/speed *
  * localVelocity} already in production, expanded component-wise - {@code dragMagnitude} is
  * {@code submergedFraction*dragCoefficient*speed*speed}, so {@code -dragMagnitude/speed} is
  * {@code -submergedFraction*dragCoefficient*speed}); passing {@code axisMultiplier=1} for every
  * axis therefore reproduces that exact isotropic force component-for-component, the "default 1
  * preserving current scalar waterDrag" equivalence this ticket requires. Deliberately uses the
  * FULL point speed (not the axis's own component magnitude) in every axis's own formula, same
  * as the isotropic baseline - only the per-axis multiplier varies the strength, not the
  * underlying quadratic-in-overall-speed shape, so this stays dissipative (opposes
  * {@code axisVelocityComponent}'s own sign, never flips it) for any nonnegative multiplier,
  * exactly like the isotropic baseline already is.
  */
 public static double directionalDragForce(double speed, double axisVelocityComponent, double submergedFraction, double dragCoefficient, double axisMultiplier) {
  return -submergedFraction * dragCoefficient * axisMultiplier * speed * axisVelocityComponent;
 }
 /**
  * MINECRAFT-545/547 (John's comment 54680): a production-relevant dissipativity criterion for
  * the explicit per-float drag impulse {@code BuoyantForces.apply} currently applies
  * ({@code directionalDragForce(...).mul(DT)} via {@code applyImpulseAtPoint}) - NOT a claim
  * about the full rigid-body response (that needs the body's own moment of inertia and the
  * coupling between floats, which this pure function does not have access to; see
  * {@code BuoyantForcesOvershootTest}'s own explicit COM-only scope note). This is the classical
  * explicit-Euler linear-stability bound, applied by linearizing the quadratic drag
  * {@code F=-fraction*coefficient*axisMultiplier*speed*v} around the CURRENT {@code speed}
  * (treated as locally fixed for one step, the same approximation
  * {@link #angularDragRetention}'s own closed-form solution already makes for the rotational
  * term): the effective per-step damping rate is {@code k_eff=fraction*coefficient*
  * axisMultiplier*speed}, and a single explicit step with mass {@code mass} is linearly stable
  * (does not overshoot past zero and grow) exactly when {@code k_eff*dt/mass < 2} - the same
  * textbook bound used for any explicit integration of {@code dv/dt=-k*v}. Returns that
  * dimensionless margin directly (not merely a boolean) so a caller can see HOW far past (or
  * under) the threshold a given step actually is, not only whether it crossed it.
  */
 public static double explicitDragStabilityMargin(double speed, double submergedFraction, double dragCoefficient, double axisMultiplier, double dt, double mass) {
  return submergedFraction * dragCoefficient * axisMultiplier * speed * dt / mass;
 }
 /** True only if {@link #explicitDragStabilityMargin} stays under the classical explicit-Euler
  * linear-stability bound of {@code 2.0} - see that method's own doc for the derivation and its
  * explicit COM-only, single-float scope. */
 public static boolean isExplicitDragStepStable(double speed, double submergedFraction, double dragCoefficient, double axisMultiplier, double dt, double mass) {
  return explicitDragStabilityMargin(speed, submergedFraction, dragCoefficient, axisMultiplier, dt, mass) < 2.0;
 }
 /**
  * MINECRAFT-545/547 (John's comment 54680: "assess a bounded stable integration option
  * compatible with existing APIs"). PROPOSED alternative to the current explicit per-float drag
  * IMPULSE - NOT yet wired into {@code BuoyantForces.apply}; this is a candidate for mechanism/
  * diff review, mirroring {@link #angularDragRetention}'s own already-reviewed closed-form
  * pattern exactly, applied to the per-axis LINEAR directional drag term instead of the
  * rotational one. Same derivation: the exact analytic solution of
  * {@code dv/dt = -fraction*coefficient*axisMultiplier*speed*v} (linearizing {@code speed} as
  * locally fixed for one step, same assumption {@link #angularDragRetention} already makes) is
  * {@code v(t+dt) = v(t) / (1 + fraction*coefficient*axisMultiplier*speed*dt)} - a MULTIPLICATIVE
  * velocity scale, never an impulse, mathematically guaranteed to lie in {@code (0,1]} for any
  * nonnegative inputs (denominator always {@code >= 1}), so a caller multiplying the CURRENT
  * per-axis velocity component by this fraction can never overshoot past zero, reverse its sign,
  * or add energy, for ANY configured coefficient/speed/dt - not just within some additionally-
  * clamped "safe" range, and with no moment of inertia needed (unlike the current impulse
  * approach, which this ticket's own investigation found silently assumes the COM-only response
  * is the full point response - see VERIFICATION.md). A zero coefficient still returns exactly
  * {@code 1} (no change), reproducing the exact current behavior whenever drag is configured
  * off, same as {@link #angularDragRetention}'s own zero-coefficient case. */
 public static double directionalDragRetention(double speed, double submergedFraction, double dragCoefficient, double axisMultiplier, double dt) {
  return 1.0 / (1.0 + submergedFraction * dragCoefficient * axisMultiplier * speed * dt);
 }
 /**
  * Per-tick retention fraction (always in (0,1]) for rotational water drag - the rotational
  * analogue of {@link #drag} for roll/pitch/yaw, applied as a direct decay on angular velocity
  * itself, never an explicit torque/impulse. MINECRAFT-518: today a floating hull gets a righting
  * torque only as an emergent side effect of {@code drag} applied at each float device's own
  * off-center position (see {@code BuoyantForces}'s class doc); nothing damps rotation itself, so
  * a hull knocked into a spin (e.g. by a collision) has no hydrodynamic resistance arresting that
  * spin beyond those per-float linear-drag side effects. {@code angularDragCoefficient} is an
  * inertia-normalized angular decay strength (not a torque/Nm coefficient - see the review finding
  * below for why no moment of inertia is involved anywhere in this formula or its caller).
  *
  * <p>Review finding (John, ticket comment 53679): an earlier version of this method returned an
  * explicit quadratic TORQUE magnitude, applied as an impulse via {@code RigidBodyHandle
  * .applyAngularImpulse}. Converting that impulse to a velocity change divides by the body's own
  * moment of inertia - a quantity {@code RigidBodyHandle} exposes no way to read, so the
  * resulting delta-omega was inertia-UNAWARE. For a light/low-inertia hull and a coefficient
  * anywhere in this field's own documented valid range, that explicit update can overshoot past
  * zero and reverse the hull's spin while ADDING rotational energy (review's own worked example:
  * c=100000, fraction=1, I=1000 kg*m^2, omega=1 rad/s yields omega'=-4 rad/s - 4x the speed, flipped
  * sign) - a genuine instability, not merely an approximation error.
  *
  * <p>Fixed by changing what this method computes and how its result is applied: instead of a
  * torque converted through an unknown inertia, this returns the EXACT analytic solution of the
  * quadratic-drag ODE {@code d(omega)/dt = -coefficient*fraction*omega*|omega|} over one tick of
  * {@code dt} - {@code omega(t+dt) = omega(t) / (1 + coefficient*fraction*|omega(t)|*dt)} is not
  * merely a numerical-integration CHOICE (e.g. one particular implicit/semi-implicit Euler
  * discretization among several reasonable ones) but the closed-form solution of that ODE itself
  * (review correction, comment 53689, of an earlier "backward-Euler" characterization here - this
  * formula is exact for this specific ODE, not an approximation scheme). The retention fraction
  * this returns, {@code 1 / (1 + coefficient*fraction*|omega(t)|*dt)}, is mathematically
  * guaranteed to lie in {@code (0,1]} for every nonnegative {@code angularDragCoefficient}/{@code
  * submergedFraction}/{@code angularSpeed}/{@code dt} - the denominator is always {@code >= 1} -
  * so the caller (which multiplies the body's OWN current angular velocity by this fraction, a
  * direct velocity scale via {@code RigidBodyHandle.addLinearAndAngularVelocity}, never an
  * impulse) can never remove more than 100% of the existing spin, never reverse its sign, and
  * never add rotational energy, for ANY configured coefficient - not just within some
  * additionally-clamped "safe" range. This also sidesteps the missing-inertia problem entirely: no
  * moment of inertia is needed anywhere in this formula or its caller. A zero coefficient (the
  * documented fallback) still returns exactly 1 (no change at all), reproducing the exact prior
  * behavior.
  *
  * <p>Second review finding (John, comment 53689): the caller must apply this against the body's
  * CURRENT (freshly-read) angular velocity, not a snapshot captured before this same tick's other
  * impulses - see {@code BuoyantForces.apply}'s own doc for that fix; this method itself is a pure
  * function and takes {@code angularSpeed} as a plain parameter either way. */
 public static double angularDragRetention(double angularSpeed, double submergedFraction, double angularDragCoefficient, double dt) {
  return 1.0 / (1.0 + submergedFraction * angularDragCoefficient * angularSpeed * dt);
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
  * destroyed/removed ({@code active} - IV's own {@code isActiveVar}), actually turning
  * ({@code currentRpm} away from zero - from {@code PartPropellerAccessor}, also not public),
  * and actually producing nonzero {@link #signedPropellerDrive} - a linked, active, spinning
  * propeller with zero computed pitch/direction (feathered, or caught mid-swing through
  * dynamic pitch) must not count toward the shared budget's denominator either: it produces no
  * thrust of its own ({@code signedPropellerDrive} is 0, so {@link #propellerThrust} is 0) but
  * would otherwise still dilute the share a propeller that IS actually thrusting gets, just by
  * spinning. All four conditions are checked independently, so no single one can substitute
  * for the others. */
 public static boolean isPropellerOperational(boolean linkedToRunningEngine, boolean active, double currentRpm, double signedDrive) {
  return linkedToRunningEngine && active && Math.abs(currentRpm) > 1e-3 && signedDrive != 0;
 }
 /** Signed drive for one operational propeller: the sign of IV's own computed thrust
  * direction ({@code PartPropeller.desiredLinearVelocity}, a public field driven by
  * {@code currentPitch} - a fixed, JSON-defined sign for a static-pitch propeller, or one
  * swung between forward and reverse by {@code vehicle.reverseThrustVar} for a dynamic-pitch
  * one), times vehicle throttle for magnitude. A propeller has no gearbox of its own:
  * transmission gear state ({@code PartEngine.currentGearVar}) selects direction for driven
  * wheels, not for a propeller linked to the same engine, so it must not be consulted here -
  * using it produced wrong-direction or falsely-zero thrust whenever gear and propeller pitch
  * disagreed (e.g. an engine in neutral still spinning a propeller, or a dynamic-pitch
  * propeller reversed via {@code reverseThrustVar} while the transmission stayed in a forward
  * gear). Zero {@code desiredLinearVelocity} (no computed direction yet) yields zero drive. */
 public static double signedPropellerDrive(double desiredLinearVelocity, double throttle) {
  return Math.signum(desiredLinearVelocity) * throttle;
 }
}
