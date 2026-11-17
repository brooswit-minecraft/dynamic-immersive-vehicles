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
  * MINECRAFT-545/547 (John's comments 54680/54684 - 54684 found two real errors in the first
  * version of this proposal, both confirmed numerically before this fix). STILL a PROPOSAL, NOT
  * wired into {@code BuoyantForces.apply} - a candidate for its own mechanism/diff review, not a
  * production change.
  * <p>CORRECTED (54684, error 1 - missing mass): the existing {@code directionalDragForce}
  * returns a FORCE (N), not an already-mass-normalized velocity rate - translational
  * {@code dv/dt = F/mass} requires dividing by {@code mass}, which the first version of this
  * method omitted entirely, silently reusing the force coefficient as if it were already a rate
  * constant. {@link #angularDragRetention} avoids this by design - its own
  * {@code angularDragCoefficient} parameter is explicitly documented as "inertia-normalized...
  * not a torque/Nm coefficient," so no separate normalization step is needed there; copying its
  * formula SHAPE without also carrying that same normalization was not dimensionally valid.
  * Confirmed numerically at this incident's own real values (mass=400, coefficient=400,
  * speed=43.90, dt=0.05): the first version returned {@code 1/879 ~ 0.00114}; the mass-corrected
  * version below returns {@code 1/3.195 ~ 0.313}, matching the real translational drag law.
  * <p>CORRECTED (54684, error 2 - mislabeled "exact"): for a FIXED rate {@code k=
  * fraction*coefficient*axisMultiplier*speed/mass} (the linearization this method already makes,
  * treating {@code speed} as locally fixed for one step - the same assumption
  * {@link #angularDragRetention} makes for ITS OWN, genuinely quadratic-in-the-evolving-variable
  * ODE), the TRUE exact solution of {@code dv/dt=-k*v} is the EXPONENTIAL
  * {@code v(t+dt)=v(t)*exp(-k*dt)} - not {@code v(t)/(1+k*dt)}, which is instead the BACKWARD
  * (implicit) EULER discretization of that same linearized ODE. {@code angularDragRetention}'s
  * own {@code 1/(1+...)} form is genuinely exact for ITS OWN ODE because that ODE is quadratic in
  * the evolving variable itself ({@code domega/dt=-k*omega*|omega|}); this method's own ODE is
  * linear in the evolving variable (speed is a separate, frozen parameter), so the same algebraic
  * form here is backward Euler, not an exact solution - stated as such below, not claimed
  * otherwise.
  * <p>NOT CLAIMED (54684, error 3 - mechanism preservation): applying this as a direct
  * multiplicative scale to a body's COM velocity is NOT a mechanism-preserving replacement for
  * the current per-float off-center impulse, which produces BOTH a translational AND a
  * rotational (torque, via each float's own {@code arm}) response together. A pure COM-velocity
  * scale would remove or change that angular coupling; this method does not define how to
  * reproduce it, and this file does not claim "complete rigid-body energy safety" from these
  * values alone - only that THIS bounded scalar form, in isolation, cannot overshoot past zero or
  * reverse sign. Reproducing the SAME off-center torque coupling in a provably bounded way would
  * need the body's own moment of inertia, which {@code RigidBodyHandle} does not expose (the
  * same missing-inertia limitation {@link #angularDragRetention}'s own doc already names) - a
  * genuinely unresolved gap in the currently supported pinned API, not solved by this method. */
 public static double directionalDragRetention(double speed, double submergedFraction, double dragCoefficient, double axisMultiplier, double dt, double mass) {
  return 1.0 / (1.0 + submergedFraction * dragCoefficient * axisMultiplier * speed * dt / mass);
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
 /**
  * MINECRAFT-545/547 (John's comment 54691): the energy-based dissipativity check for an
  * impulse applied along a unit direction {@code n} at a point whose PRE-IMPULSE point-velocity
  * component along {@code n} is {@code preImpulsePointVelocityAlongDirection} - for impulse
  * magnitude {@code impulseMagnitude} (signed: positive means applied in the {@code +n}
  * direction) and {@code inverseEffectiveMass} the directional inverse effective mass AT THAT
  * POINT (from {@code MassData.getInverseNormalMass(position, n)} - the real translation+
  * rotation-coupled effective mass, not a COM-only approximation), the resulting kinetic-energy
  * CHANGE is {@code impulseMagnitude*preImpulsePointVelocityAlongDirection +
  * 0.5*inverseEffectiveMass*impulseMagnitude^2} - the standard rigid-body work-energy impulse
  * formula. Dissipative (non-energy-adding) exactly when this is {@code <= 0}.
  * <p>SCOPE, stated explicitly: this is a SINGLE impulse against a SINGLE PRE-IMPULSE velocity
  * snapshot. CORRECTED (John's comment 54694 - a confirmed, concrete math error in an earlier
  * version of this file's own multi-float test/claim, not merely a documentation gap): summing
  * {@code impulseEnergyChange} evaluated SEPARATELY for two-or-more impulses against the SAME
  * snapshot does NOT predict the actual combined energy change, REGARDLESS of whether the
  * native side applies them immediately or defers/accumulates them - {@code
  * 0.5*k*(J1+J2)^2 != 0.5*k*J1^2 + 0.5*k*J2^2} (the quadratic cross term {@code k*J1*J2} is
  * real and is exactly what the "sum the helper per impulse" approach silently drops). The
  * CORRECT combined prediction for two co-linear impulses against the same snapshot is this
  * SAME function called ONCE with their SUM, {@code impulseEnergyChange(J1+J2, s, k)} - not the
  * sum of two separate calls. There is NO multi-impulse safety claim in this file beyond that:
  * bounding several individual impulses independently does not bound their combined effect. */
 public static double impulseEnergyChange(double impulseMagnitude, double preImpulsePointVelocityAlongDirection, double inverseEffectiveMass) {
  return impulseMagnitude * preImpulsePointVelocityAlongDirection + 0.5 * inverseEffectiveMass * impulseMagnitude * impulseMagnitude;
 }
 /** True only if {@link #impulseEnergyChange} is {@code <= 0} for these exact values - the
  * direct dissipativity check, not merely a sign-reversal/zero-crossing heuristic. */
 public static boolean isImpulseDissipative(double impulseMagnitude, double preImpulsePointVelocityAlongDirection, double inverseEffectiveMass) {
  return impulseEnergyChange(impulseMagnitude, preImpulsePointVelocityAlongDirection, inverseEffectiveMass) <= 0;
 }
 /**
  * The largest-MAGNITUDE impulse, applied OPPOSING {@code preImpulsePointVelocityAlongDirection}
  * (i.e. with sign {@code -Math.signum(preImpulsePointVelocityAlongDirection)}), that stays
  * energy-dissipative per {@link #impulseEnergyChange} - the non-trivial root of that quadratic
  * in impulse magnitude, {@code 2*|s|/k} (derived by substituting the opposing-sign impulse into
  * the energy formula and solving for the non-zero root).
  * <p>CORRECTED (John's comment 54694): this is an ENERGY-neutral bound, NOT a no-reversal/non-
  * overshoot bound - a prior version of this doc conflated the two. AT this exact bound, the
  * velocity component along {@code n} REVERSES SIGN with the SAME magnitude ({@code s -> -s}) -
  * confirmed algebraically: substituting {@code J=-sign(s)*2|s|/k} into {@code v'=s+J*k} gives
  * {@code v'=-s}. This bound guarantees zero net KINETIC-ENERGY change, nothing about velocity
  * DIRECTION or magnitude staying put - a real, stated numerical/model tradeoff, not an
  * incidental detail. A caller bounding a real impulse against this value gets a PHYSICALLY
  * DERIVED, not arbitrary, bound on ENERGY ADDITION specifically - stated with that exact scope,
  * and (per {@link #impulseEnergyChange}'s own corrected scope note) does NOT by itself bound
  * multiple impulses' own COMBINED effect; each must still be evaluated together via that same
  * method, never summed separately.
  * <p>{@code inverseEffectiveMass} or {@code preImpulsePointVelocityAlongDirection} not a
  * positive-finite / finite value respectively (CORRECTED, 54694: an earlier version only
  * guarded {@code inverseEffectiveMass}, not {@code preImpulsePointVelocityAlongDirection} - a
  * non-finite {@code s} with a valid {@code k} previously propagated NaN/Infinity straight
  * through, not a conservative {@code 0}) returns {@code 0} - no positive bound is well-defined
  * without both a finite velocity component and a positive, finite effective mass to divide by.
  * <p>CORRECTED (54697): finite VALID inputs can still produce a non-finite RESULT via overflow
  * ({@code 2*|s|/k} overflows {@code double} range for, e.g., {@code s=Double.MAX_VALUE, k=1}) -
  * the input guard above does not catch this. The result itself is now also guarded: a non-
  * finite computed bound returns the conservative {@code 0}, same as every other invalid case
  * here, rather than silently handing a caller an {@code Infinity} "bound". */
 public static double maxDissipativeImpulseMagnitude(double preImpulsePointVelocityAlongDirection, double inverseEffectiveMass) {
  if (!(inverseEffectiveMass > 0) || !Double.isFinite(inverseEffectiveMass) || !Double.isFinite(preImpulsePointVelocityAlongDirection)) return 0;
  double bound = 2 * Math.abs(preImpulsePointVelocityAlongDirection) / inverseEffectiveMass;
  return Double.isFinite(bound) ? bound : 0;
 }
}
