package io.github.brooswitminecraft.div;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import net.neoforged.fml.loading.FMLPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
/**
 * Optional per-car tuning/override, keyed by packID:systemName. Physics now applies to every
 * eligible vehicle by default (see PhysicsBridge); a car needs no entry here at all. An entry's
 * {@code enabled} is the one activation-relevant field, and it is an opt-OUT only: leaving it
 * unset (or absent) uses the default classification-based behavior, {@code true} is a no-op kept
 * for backwards compatibility with earlier opt-in configs, and {@code false} excludes that one
 * car even if its category would otherwise be eligible. Every other field only tunes an already-
 * eligible car. Restart to reload.
 */
public final class Overrides {
 /**
  * Sparse by design (ticket comment 53181): every tunable field but {@code enabled} is a boxed
  * {@code Double}/{@code Boolean}, not a primitive with a literal default - so Gson leaves an
  * OMITTED field {@code null} instead of silently filling in today's shared number. That null is
  * the signal {@link #resolved} needs to fall through to the next precedence level (pack DIV
  * metadata, an IV-adapted native value, a physically-derived value, then the documented
  * fallback below) rather than an operator's config looking like it explicitly chose 8000N drive
  * force when it never mentioned drive force at all. Only the fallback level exists today - the
  * other three are intentionally not wired yet (ticket comment 53177/53179/53181: native
  * drive/brake/steering/tire semantics and the pack-metadata file format are still being
  * resolved) - {@link #resolved} already has the right shape to add them without another
  * breaking change to this class.
  */
 public static final class Tuning {
  /**
   * Ticket 53518 (MINECRAFT-505): names a {@link #templates} entry this Tuning inherits from -
   * every field THIS entry leaves {@code null} falls through to that template's own resolved
   * value (which may itself extend another template, chained) before the existing operator-
   * override-vs-pack/native/fallback precedence in {@link #resolved}/{@link #resolvedPart} ever
   * runs. {@code null}/absent means no inheritance at all - today's exact prior behavior for
   * every entry already in a loaded config. See {@link #effective} for the resolution/cycle-
   * detection logic; {@link #load}/{@link #reload} both eagerly resolve every entry's chain once
   * at load time so a bad target or a cycle fails loudly then, not on some later vehicle's first
   * tick.
   */
  @SerializedName("extends") public String extendsTemplate;
  public Boolean enabled;
  public Double massKg;
  public Double suspensionTravel;
  public Double friction;
  // MINECRAFT-534: installed-tire rolling-resistance multiplier, independent of friction/grip -
  // see PhysicsBridge.tirePartRollingResistance's own doc for the composition. Vehicle-level,
  // same shape as friction: resolves to the flat FALLBACK (1.0, a true no-op reproducing today's
  // exact ground-surface-only rollingResistanceMultiplier byte-identically) when unconfigured at
  // every precedence level; a part-level entry for THIS SPECIFIC installed tire REPLACES (never
  // multiplies) the vehicle's own resolved value for that one wheel's contact, exactly like
  // friction's own part-level precedence (replacement, not a modifier). Composed MULTIPLICATIVELY
  // with the existing ground-surface rollingResistanceMultiplier by PhysicsBridge.stepGround,
  // never inside MultiContactFrictionSolver itself - no change to that solver's own rolling-
  // resistance formula (F=-k*v*rollingResistanceMultiplier) was needed or made.
  public Double rollingResistance;
  public Double driveForce;
  public Double brakeForce;
  public Double maxSteerDegrees;
  // Float-only hulls (boats/pontoons) only: hullFillFactor shrinks the chassis bounding-box
  // volume down to the fraction of it the actual hull displaces (a real hull is not a solid
  // box), waterDrag scales quadratic water drag, and propellerForce is the thrust budget split
  // across installed propellers.
  public Double hullFillFactor;
  public Double waterDrag;
  public Double propellerForce;
  // MINECRAFT-518: rotational (roll/pitch/yaw) water-drag strength for a floating hull - the
  // rotational analogue of waterDrag, damping the hull's own angular velocity directly rather
  // than only as a side effect of waterDrag applied at each float's off-center position. An
  // inertia-normalized angular decay strength (review correction, comment 53689), not a torque/
  // Nm coefficient - see Buoyancy.angularDragRetention's own doc for why no moment of inertia is
  // involved anywhere in its formula. 0 (the documented FALLBACK) reproduces the exact prior
  // behavior - no dedicated
  // rotational damping term at all. Like waterDrag/propellerForce, re-read live every tick by
  // BuoyantForces.apply - see PhysicsBridge.applyLiveSafeFields.
  public Double angularWaterDrag;
  // Opt-in self-righting assist (ticket comment 53182/53188) - ALL FIVE must be explicitly set
  // together for the assist to activate at all (see Overrides.selfRightingConfig); there is no
  // flat fallback for any of these fields, unlike the physics defaults above - an unconfigured
  // vehicle gets no assist, full stop. Units/semantics match io.github.brooswitminecraft.div.
  // experimental.SelfRightingAssist.Config exactly (strength N*m, damping N*m*s/rad, maxTorque
  // N*m, both fade angles in degrees up to that class's own HARD_SAFETY_CAP_DEGREES).
  public Double selfRightStrength;
  public Double selfRightDamping;
  public Double selfRightMaxTorque;
  public Double selfRightFadeStartDegrees;
  public Double selfRightFadeEndDegrees;
  // Two-half chassis (ticket comment 53236/53240) - upper-half mass fraction f, defaulting to
  // 0.5 (an even split) via FALLBACK below, same simple-field shape as suspensionTravel/friction/
  // etc., NOT an all-or-nothing group like selfRight*. See TwoBodyChassis.Config for the actual
  // geometry/mass/inertia math this value feeds; io.github.brooswitminecraft.div.Overrides#
  // twoBodyConfig builds that Config from a resolved Tuning, validating against the vehicle's
  // real total mass (not just f in isolation - see TwoBodyChassis.Config.validateAgainstTotalMass's
  // own doc for why that distinction matters).
  public Double upperMassFraction;
  // Ticket 53331/53332/53335/53337: John's hands-on feedback ("flops over to the side when I
  // turn...also maybe less grip or side force from the tires so the thing slides, rather than
  // violently flipping") - the lateral semi-axis of an ANISOTROPIC (elliptical) friction budget
  // in TireForces.frictionAnisotropic/MultiContactFrictionSolver, shrinking ONLY lateral tire
  // authority (normal*friction*lateralGripMultiplier) while the longitudinal semi-axis
  // (normal*friction, drive/brake) stays exactly what the existing shared `friction` field
  // already gives - per John's own explicit caution against "blindly reducing shared friction
  // if that also destroys drive/brake grip". An earlier design scaling the lateral correction
  // INSIDE the solver's own per-pass PGS loop was reviewed and rejected (53335) as unsound: it
  // only damps convergence RATE, so a high iteration count (the real installed baseline's own
  // 64, confirmed via 53333) would restore nearly full grip regardless of the configured value.
  // 1.0 (FALLBACK) delegates directly to the original circular model (TRUE byte-identical, not
  // merely algebraically close - see frictionAnisotropic's own doc for why that distinction
  // matters); values below 1.0 shrink the ellipse's lateral extent, letting the tire slide
  // sideways sooner. NOT universally longitudinal-neutral in every regime (frictionAnisotropic's
  // own doc): a genuinely combined high-demand tick (e.g. hard braking mid-turn) can still see
  // the shared radial projection reduce achieved longitudinal force somewhat.
  public Double lateralGripMultiplier;
  // MINECRAFT-519: optional, bounded axle anti-roll coupling (see AntiRoll's own doc for the
  // geometric pairing and the exact differential-travel transfer math) - a dimensionless 0 ("no
  // coupling at all", the documented FALLBACK, exact byte-identical default/zero equivalence) to
  // 1 (the maximum supported restoring-transfer strength) coupling strength. This AMPLIFIES the
  // pair's existing roll-induced load difference (a real anti-roll bar's own effect) - it does
  // NOT equalize the two loads, and with unequal calibrated spring rates (an asymmetric axle) it
  // cannot generally equalize them even at 1.0 (review correction, comment 53781: an earlier
  // version of this doc wrongly described 1.0 as "full equalization"). 0 is the FALLBACK
  // deliberately (ticket's "default zero/off preserving accepted handling") - unlike
  // suspensionTravel/friction/etc. this feature did not exist before this ticket, so there is no
  // prior shared non-zero number to preserve, only "no effect at all" to preserve exactly.
  public Double antiRollStiffness;
  // MINECRAFT-521: bounded fixed-wing lift/stall-cap and control-authority tuning, consumed by
  // PhysicsBridge.stepFlight via FlightForces.liftCoefficient/controlSurfaceForce. stallAngleDegrees
  // is the hard stall cutoff (degrees) FlightForces.liftCoefficient clamps angle of attack to;
  // liftSlope is the wing's own per-radian lift-curve slope; controlSurfaceSlope is the
  // independently-tunable per-radian aileron/elevator/rudder authority slope (previously the same
  // hardcoded constant as liftSlope - see FlightForces's own doc). None of these affect a
  // rotorcraft (stepRotor has no wing/control-surface model to apply them to) or a ground vehicle.
  public Double stallAngleDegrees;
  public Double liftSlope;
  public Double controlSurfaceSlope;
  // MINECRAFT-522: rotorcraft-only cyclic/yaw tuning, mirroring RotorForces.cyclicTilt's own
  // bounded-clamp contract and RotorForces.cyclicTorque/yawTorque's new authority-scale overloads
  // - see those methods' own docs. cyclicLimitDegrees bounds how far EACH AXIS (pitch and roll
  // independently, matching the class's prior hardcoded 20-degree clamp exactly) of the disk can
  // be tilted; it is not a combined/total-disk-angle bound - the combined tilt (hypot of both
  // axes) can exceed this number when both axes are near their limit simultaneously, unchanged
  // from the model's original per-axis-clamp behavior (John's review, comment 53751).
  // cyclicAuthorityScale/yawAuthorityScale multiply the already thrust-gated cyclic/yaw torque,
  // never granting unpowered free torque. All three re-read live every tick by
  // PhysicsBridge.stepRotor, same as driveForce/maxSteerDegrees - see
  // PhysicsBridge.applyLiveSafeFields.
  public Double cyclicLimitDegrees;
  public Double cyclicAuthorityScale;
  public Double yawAuthorityScale;
  // MINECRAFT-520: configurable front/rear SERVICE-brake distribution - 0.0 sends the whole
  // tuning.brakeForce budget to the geometric rear axle group, 1.0 sends it all to the front
  // group, 0.5 is the neutral/even axle split. No FALLBACK entry (like selfRight*/massKg/
  // enabled above, NOT like brakeForce/friction/etc.) - null/unset means "no operator bias
  // configured at all", which PhysicsBridge/BrakeBiasDistribution treats as the exact pre-
  // ticket behavior (plain brakeForce/wheelCount for every wheel), not merely a 0.5 default;
  // see BrakeBiasDistribution's own doc for why an explicit 0.5 is a materially different claim
  // from "unset" on an asymmetric axle-count vehicle. Deliberately independent of the parking
  // brake and the separate analog handbrake - see PhysicsBridge.perWheelBrakeMagnitude's own doc
  // for why those two never get redistributed by this bias.
  public Double brakeBiasFront;
  // MINECRAFT-524: configurable front/rear DRIVE-force distribution, same shape as
  // brakeBiasFront immediately above (same geometric front/rear axle grouping, same
  // conservation, same fallback) but for tuning.driveForce's budget instead of brakeForce's -
  // see DriveBiasDistribution's own doc for the full reasoning, deliberately mirrored field-for-
  // field. 0.0 sends the whole drive budget to the geometric rear axle group, 1.0 sends it all
  // to the front group, 0.5 is the neutral/even axle split. No FALLBACK entry (like
  // brakeBiasFront) - null/unset means "no operator bias configured at all", reproducing the
  // exact pre-ticket behavior (plain driveForce/wheelCount for every wheel), not merely a 0.5
  // default. This is a geometric budget split only - not a differential, not traction control,
  // and not a gear-ratio simulation (see DriveBiasDistribution's own doc).
  public Double driveBiasFront;
  // MINECRAFT-523: optional, per-vehicle dimensionless suspension damping ratio - the critical-
  // damping-ratio multiplier PhysicsBridge.suspensionDamperForShare/suspensionDamper/
  // effectiveMassDamper already apply (2*sqrt(spring*mass)*ratio), previously a hardcoded
  // 0.7 shared by every vehicle. 0.7 (FALLBACK below) reproduces that exact prior constant - a
  // byte-identical default equivalence for every vehicle that never sets this field, same as
  // suspensionTravel/friction/etc. 1.0 is critical damping (fastest non-oscillating settle);
  // below 1.0 (toward the strictly-positive lower bound) is progressively underdamped
  // (oscillates more before settling); above 1.0 (up to the documented upper bound) is
  // progressively overdamped (settles without oscillating, but more sluggishly). Re-read live
  // every tick by PhysicsBridge.stepGround, same as brakeBiasFront - see
  // PhysicsBridge.applyLiveSafeFields. Explicitly shared with the opt-in experimental
  // effective-mass damping candidate (-Ddiv.experimentalEffectiveMassDamping, see
  // PhysicsBridge.effectiveMassDamper's own doc) - when that candidate is active it still uses
  // THIS configured ratio, never a second hardcoded 0.7, so a configured value never silently
  // disappears depending on whether that unrelated experimental candidate happens to be enabled.
  public Double suspensionDampingRatio;
  // MINECRAFT-528: optional, per-vehicle dimensionless suspension SPRING-stiffness multiplier -
  // uniformly scales the calibrated per-wheel spring rate PhysicsBridge.suspensionSpring/
  // suspensionSpringForShare already compute (mass*gravity*loadShare/(travel*.5)), previously
  // never configurable at all. 1.0 (FALLBACK below) reproduces that exact calibrated rate - a
  // byte-identical default equivalence for every vehicle that never sets this field, same shape
  // as suspensionDampingRatio immediately above. UNLIKE suspensionDampingRatio, this is NOT a
  // settling-behavior-only knob: the spring rate itself sets the static ride-height equilibrium
  // (PhysicsBridge.suspensionSpring's own doc - compression balances gravity at exactly
  // travel*.5 for the CALIBRATED rate), so scaling it moves that equilibrium. A multiplier m
  // settles to compression (travel*.5)/m instead: m>1 (stiffer) sits HIGHER (less compression,
  // a harder/sportier ride), m<1 (softer) sits LOWER (more compression, a plusher/softer ride).
  // Review finding: m<=0.5 is not merely "soft" - it is PHYSICALLY UNREACHABLE. Equilibrium
  // (travel*.5)/m must stay strictly below suspensionTravel itself (the hard compression ceiling
  // PhysicsBridge's raycast length and TireForces.suspension's own geometry enforce; damping
  // supplies no static support at zero velocity, so it cannot make up a shortfall at rest) -
  // algebraically (travel*.5)/m < travel reduces to m > 0.5, independent of suspensionTravel's
  // own configured value entirely (the travel cancels out). At or below 0.5 the wheel has no
  // static rest position short of fully bottomed out (zero remaining travel, and strictly below
  // 0.5 the vehicle's real weight permanently exceeds what the spring can ever support at rest,
  // regardless of travel) - rejected here, not merely documented as a caveat. Lower-bounded at
  // a strict 0.5 (never inclusive, same "reject the exact boundary too" reasoning
  // suspensionDampingRatio's own STABILITY_MARGIN uses - 0.5 exactly is already fully bottomed,
  // not a working suspension), capped at 4.0 as a documentable flat ceiling on the stiff end;
  // the REAL stability guard on the stiff end is the travel/damping-ratio-aware cross-field check
  // below (see maxStableSuspensionDampingRatio's own doc, now generalized to take this
  // multiplier too) - scaling the spring raises the per-wheel natural frequency by sqrt(m),
  // which can destabilize Body.stepGround's explicit-damping-term integration exactly the same
  // way an excessive suspensionDampingRatio alone already could, so the two fields share one
  // combined stability check rather than two independent ones that could each pass alone while
  // their combination does not. Re-read live every tick by PhysicsBridge.stepGround, same as
  // suspensionDampingRatio/brakeBiasFront - see PhysicsBridge.applyLiveSafeFields.
  public Double suspensionSpringMultiplier;
  // MINECRAFT-533: optional, per-vehicle static toe alignment angle (degrees) for the geometric
  // front/rear axle group (same grouping as brakeBiasFront/driveBiasFront - see
  // AxleToeDistribution's own doc), applied to each wheel's own contact tangent forward/right
  // frame (PhysicsBridge.stepGround's `steer`/`forward`/`right`), mirrored left/right by anchor
  // x-sign so a positive value is toe-IN (converging) and a negative value is toe-OUT
  // (diverging) at THAT axle - never a vehicle-wide concept, unlike maxSteerDegrees. No FALLBACK
  // entry (same shape as brakeBiasFront/driveBiasFront) - null/unset means "no operator toe
  // configured at all" for that axle, which AxleToeDistribution treats as the exact pre-ticket
  // zero-offset behavior, not merely a 0.0 default (a vehicle that never sets either field is
  // byte-identical to before this ticket). Re-read live every tick by PhysicsBridge.stepGround -
  // see PhysicsBridge.applyLiveSafeFields.
  public Double toeFrontDegrees;
  // Same shape as toeFrontDegrees immediately above, for the geometric rear axle group instead.
  public Double toeRearDegrees;
  // MINECRAFT-532: opt-in contact-aware axle drive-force sharing - see AxleDriveForceSharing's
  // own doc for the full redistribution reasoning. null/false (the documented default) is a
  // byte-identical no-op: unset reproduces the exact pre-ticket per-wheel drive allocation,
  // same "unset means untouched" shape driveBiasFront/brakeBiasFront already use, not merely a
  // conservative default value. true opts a vehicle INTO redistributing an airborne wheel's own
  // now-unusable drive share to its grounded same-axle wheel(s), weighted by their real measured
  // contact load - never across the front/rear axle boundary. Re-read live every tick by
  // PhysicsBridge.stepGround, same as driveBiasFront - see PhysicsBridge.applyLiveSafeFields.
  public Boolean axleDriveForceSharing;
  /** Validates only fields this entry actually SET - an omitted (null) field is exactly as
   * valid as a present one; it just means "no operator override at this precedence level",
   * never "zero" or "the documented fallback value" implicitly. */
  public void validate() {
   if (massKg != null && (!Double.isFinite(massKg) || massKg < 50 || massKg > 100000)) throw new IllegalArgumentException("massKg out of range");
   if (suspensionTravel != null && !(suspensionTravel > 0 && suspensionTravel <= 1)) throw new IllegalArgumentException("invalid vehicle tuning");
   if (friction != null && !(friction > 0 && friction <= 3)) throw new IllegalArgumentException("invalid vehicle tuning");
   // MINECRAFT-534: strictly positive, same "never zero" reasoning friction's own bound above
   // documents - PhysicsBridge.tirePartRollingResistance divides a resolved value back by this
   // same vehicle-resolved rollingResistance to form a pure ratio (mirroring tirePartFriction's
   // own division by tuning.friction), so a zero value would be a division-by-zero. Capped at 10
   // to match GroundSurfaceOverrides' own rollingResistanceMultiplier range - a consistent bound
   // across both the ground-surface and tire-part multipliers that compose together.
   if (rollingResistance != null && !(rollingResistance > 0 && rollingResistance <= 10)) throw new IllegalArgumentException("invalid vehicle tuning");
   if (driveForce != null && !(driveForce >= 0 && driveForce <= 100000)) throw new IllegalArgumentException("invalid vehicle tuning");
   if (brakeForce != null && !(brakeForce >= 0 && brakeForce <= 100000)) throw new IllegalArgumentException("invalid vehicle tuning");
   if (maxSteerDegrees != null && !(maxSteerDegrees > 0 && maxSteerDegrees <= 60)) throw new IllegalArgumentException("invalid vehicle tuning");
   if (hullFillFactor != null && !(hullFillFactor > 0 && hullFillFactor <= 1)) throw new IllegalArgumentException("invalid vehicle tuning");
   if (waterDrag != null && !(waterDrag >= 0 && waterDrag <= 100000)) throw new IllegalArgumentException("invalid vehicle tuning");
   if (propellerForce != null && !(propellerForce >= 0 && propellerForce <= 100000)) throw new IllegalArgumentException("invalid vehicle tuning");
   if (angularWaterDrag != null && !(angularWaterDrag >= 0 && angularWaterDrag <= 100000)) throw new IllegalArgumentException("invalid vehicle tuning");
   if (selfRightStrength != null && !(Double.isFinite(selfRightStrength) && selfRightStrength >= 0)) throw new IllegalArgumentException("invalid vehicle tuning");
   if (selfRightDamping != null && !(Double.isFinite(selfRightDamping) && selfRightDamping >= 0)) throw new IllegalArgumentException("invalid vehicle tuning");
   if (selfRightMaxTorque != null && !(Double.isFinite(selfRightMaxTorque) && selfRightMaxTorque >= 0)) throw new IllegalArgumentException("invalid vehicle tuning");
   double hardCap = io.github.brooswitminecraft.div.experimental.SelfRightingAssist.HARD_SAFETY_CAP_DEGREES;
   if (selfRightFadeStartDegrees != null && !(Double.isFinite(selfRightFadeStartDegrees) && selfRightFadeStartDegrees >= 0 && selfRightFadeStartDegrees <= hardCap)) throw new IllegalArgumentException("invalid vehicle tuning");
   if (selfRightFadeEndDegrees != null && !(Double.isFinite(selfRightFadeEndDegrees) && selfRightFadeEndDegrees > (selfRightFadeStartDegrees!=null?selfRightFadeStartDegrees:0) && selfRightFadeEndDegrees <= hardCap)) throw new IllegalArgumentException("invalid vehicle tuning");
   // Strictly between 0 and 1, never 0/1/out-of-range - matches TwoBodyChassis.Config's own
   // constructor validation exactly, checked again here so a malformed config entry fails loudly
   // at load time rather than only when a vehicle actually tries to resolve a two-body config.
   if (upperMassFraction != null && !(Double.isFinite(upperMassFraction) && upperMassFraction > 0 && upperMassFraction < 1)) throw new IllegalArgumentException("invalid vehicle tuning");
   // Strictly between 0 (exclusive, never a true zero-lateral-grip vehicle - that would be a
   // physically nonsensical ice-skate tire) and 1 (inclusive - the no-op/unconfigured value).
   // Never above 1: this knob is explicitly for REDUCING lateral grip (John's own "less grip...
   // so it slides"), not for granting more lateral authority than the real friction circle.
   if (lateralGripMultiplier != null && !(Double.isFinite(lateralGripMultiplier) && lateralGripMultiplier > 0 && lateralGripMultiplier <= 1)) throw new IllegalArgumentException("invalid vehicle tuning");
   // Inclusive 0 (the FALLBACK/off value, explicitly valid to set - an operator may want to
   // state "no anti-roll" explicitly rather than merely omit the field) through inclusive 1 (the
   // maximum supported restoring-transfer strength) - see AntiRoll's own doc for why 1 is a real,
   // meaningful upper bound rather than an arbitrary cap. NOT "full equalization" of the pair's
   // loads (review correction, comment 53781) - this amplifies their existing roll-induced
   // difference, and with unequal calibrated rates cannot generally equalize them at any setting.
   if (antiRollStiffness != null && !(Double.isFinite(antiRollStiffness) && antiRollStiffness >= 0 && antiRollStiffness <= 1)) throw new IllegalArgumentException("invalid vehicle tuning");
   // Bounded well short of a literal post-stall simulation (ticket's own "do not claim full
   // post-stall spin/soft-body simulation" constraint): 45deg keeps this a genuine hard stall
   // CAP, never a config path to effectively disabling stall at all.
   if (stallAngleDegrees != null && !(Double.isFinite(stallAngleDegrees) && stallAngleDegrees > 0 && stallAngleDegrees <= 45)) throw new IllegalArgumentException("invalid vehicle tuning");
   // Bounded generously around the thin-airfoil-theory default (2*pi ~= 6.283) - finite, positive,
   // never so large it produces an absurd lift/control force from a tiny angle/deflection.
   if (liftSlope != null && !(Double.isFinite(liftSlope) && liftSlope > 0 && liftSlope <= 10)) throw new IllegalArgumentException("invalid vehicle tuning");
   if (controlSurfaceSlope != null && !(Double.isFinite(controlSurfaceSlope) && controlSurfaceSlope > 0 && controlSurfaceSlope <= 10)) throw new IllegalArgumentException("invalid vehicle tuning");
   // MINECRAFT-522: bounded the same way the class doc on RotorForces.MAX_CYCLIC_LIMIT_DEGREES
   // explains - a real rotor disk cannot be tilted onto its edge, so even an operator-configured
   // limit is capped there, never just "whatever value was typed".
   if (cyclicLimitDegrees != null && !(Double.isFinite(cyclicLimitDegrees) && cyclicLimitDegrees > 0 && cyclicLimitDegrees <= io.github.brooswitminecraft.div.RotorForces.MAX_CYCLIC_LIMIT_DEGREES)) throw new IllegalArgumentException("invalid vehicle tuning");
   // Authority scale multiplies already thrust-gated torque (RotorForces.cyclicTorque/yawTorque) -
   // strictly positive (a zero or negative scale would be a confusing way to disable attitude/yaw
   // authority entirely; that is what cyclicLimitDegrees/disabling the rotorcraft itself is for),
   // capped at 5x to keep this a tuning knob, not a way to grant unbounded attitude/yaw torque.
   if (cyclicAuthorityScale != null && !(Double.isFinite(cyclicAuthorityScale) && cyclicAuthorityScale > 0 && cyclicAuthorityScale <= 5)) throw new IllegalArgumentException("invalid vehicle tuning");
   if (yawAuthorityScale != null && !(Double.isFinite(yawAuthorityScale) && yawAuthorityScale > 0 && yawAuthorityScale <= 5)) throw new IllegalArgumentException("invalid vehicle tuning");
   // Inclusive at both ends (0.0 all-rear, 1.0 all-front are the documented, legitimate
   // extremes - see BrakeBiasDistributionTest's own front/rear-extreme coverage), unlike
   // lateralGripMultiplier's exclusive-zero: a brake bias of exactly 0 or 1 is a normal,
   // physically sensible configuration (e.g. a rear-brakes-only trike), not a degenerate one.
   if (brakeBiasFront != null && !(Double.isFinite(brakeBiasFront) && brakeBiasFront >= 0 && brakeBiasFront <= 1)) throw new IllegalArgumentException("invalid vehicle tuning");
   // Same inclusive [0,1] shape as brakeBiasFront above, for the same reason: an exact 0 or 1
   // drive bias (e.g. a front-wheel-drive-only or rear-wheel-drive-only configuration) is a
   // normal, physically sensible setting, not a degenerate one.
   if (driveBiasFront != null && !(Double.isFinite(driveBiasFront) && driveBiasFront >= 0 && driveBiasFront <= 1)) throw new IllegalArgumentException("invalid vehicle tuning");
   // Bounded well short of a real steering angle (maxSteerDegrees's own range) - toe is a static
   // alignment trim, not a second steering input; +-20deg is already far beyond any real-world
   // toe setting (typically under a few degrees) while still leaving room for exaggerated/arcade
   // tuning. Inclusive of 0 and of either sign (toe-out is a normal, legitimate setting, not a
   // degenerate one) - same "both signed extremes are real configurations" reasoning
   // suspensionDampingRatio/brakeBiasFront's own bounds use.
   if (toeFrontDegrees != null && !(Double.isFinite(toeFrontDegrees) && toeFrontDegrees >= -20 && toeFrontDegrees <= 20)) throw new IllegalArgumentException("invalid vehicle tuning");
   if (toeRearDegrees != null && !(Double.isFinite(toeRearDegrees) && toeRearDegrees >= -20 && toeRearDegrees <= 20)) throw new IllegalArgumentException("invalid vehicle tuning");
   // Strictly positive (a zero or negative ratio is an undamped or energy-adding oscillator -
   // never a sensible suspension, and not what "configurable damping" is meant to permit; a
   // vehicle that truly wants no settling behavior should disable physics for it instead, the
   // same reasoning cyclicAuthorityScale/yawAuthorityScale's own strictly-positive bound above
   // uses), hard-capped at 2.0 - never exceedable regardless of travel, a documentable ceiling
   // even though (see maxStableSuspensionDampingRatio's own doc) it is NOT provably stable for
   // every travel on its own; the real, travel-dependent bound below is what actually guards
   // stability.
   if (suspensionDampingRatio != null && !(Double.isFinite(suspensionDampingRatio) && suspensionDampingRatio > 0 && suspensionDampingRatio <= 2.0)) throw new IllegalArgumentException("invalid vehicle tuning");
   // Review finding: strictly positive is not enough - a multiplier at or below 0.5 is
   // physically unreachable at rest regardless of suspensionTravel (see suspensionSpringMultiplier's
   // own doc for the full "(travel*.5)/m < travel reduces to m > 0.5, travel cancels out"
   // derivation), so the lower bound is a strict 0.5 (the exact boundary itself is already fully
   // bottomed out, not a working suspension - rejected too, same "reject the exact boundary"
   // reasoning suspensionDampingRatio's own STABILITY_MARGIN uses), flat-capped at 4.0 on the
   // stiff end as a documentable ceiling - the real stiff-end stability guard is the combined
   // cross-field check below, not this flat range alone.
   if (suspensionSpringMultiplier != null && !(Double.isFinite(suspensionSpringMultiplier) && suspensionSpringMultiplier > 0.5 && suspensionSpringMultiplier <= 4.0)) throw new IllegalArgumentException("invalid vehicle tuning");
   // Review finding (comments 53839/53840): the flat 2.0 ceiling above only proves stable at
   // this project's documented default suspensionTravel (0.3) - Body.stepGround's own explicit-
   // damping-term integration has a REAL, travel-dependent stability bound (confirmed against an
   // independent settling sweep reproducing the reviewer's own numbers exactly: ratio 2.0 at
   // travel 0.2 settles into a sustained .0807-.0991 chatter against a .10 target, never
   // converging), so a flat ratio cap alone cannot bound it across suspensionTravel's own already-
   // validated (0,1] range. Reject whenever the CONFIGURED pair would already be unstable, using
   // this same entry's own suspensionTravel if set, else the documented flat fallback (0.3) as the
   // conservative assumed travel - see maxStableSuspensionDampingRatio's own doc for the formula
   // and its one remaining (pre-existing, out-of-scope) assumption.
   //
   // Review finding (comment 53844): the underlying linearized stability condition is a STRICT
   // inequality - AT the computed bound exactly, the integration's characteristic root sits at
   // magnitude exactly 1, which is a sustained, non-decaying oscillation (confirmed by an
   // independent sweep at travel 0.2's own exact bound, 1.7758240724699157: a constant
   // .093125/.106875 alternation around the .1 target, never converging), not the "still
   // stable" a non-strict `>` comparison alone would wrongly accept. STABILITY_MARGIN applies a
   // modest (0.5%) safety factor below the computed bound - confirmed by the same sweep to
   // actually converge - so this check rejects the exact boundary too, not only values past it.
   // MINECRAFT-528: this check now also triggers whenever suspensionSpringMultiplier alone is
   // configured (suspensionDampingRatio left null/default) - scaling the spring raises the same
   // per-wheel natural frequency the ratio-only check below is written against (see
   // maxStableSuspensionDampingRatio's own doc, now generalized to take the multiplier too), so
   // a configured multiplier can destabilize the UNCONFIGURED 0.7 default ratio exactly the way
   // an excessive configured ratio alone already could against the unconfigured 1.0 multiplier.
   // Both fields default to their own FALLBACK (never to "skip the check") whenever only one is
   // actually set, so this is a true combined check, not two independent ones that could each
   // pass alone while their actual configured combination does not.
   if (suspensionDampingRatio != null || suspensionSpringMultiplier != null) {
    double travelForStabilityCheck = suspensionTravel != null ? suspensionTravel : FALLBACK.suspensionTravel;
    double ratioForStabilityCheck = suspensionDampingRatio != null ? suspensionDampingRatio : FALLBACK.suspensionDampingRatio;
    double multiplierForStabilityCheck = suspensionSpringMultiplier != null ? suspensionSpringMultiplier : FALLBACK.suspensionSpringMultiplier;
    if (ratioForStabilityCheck > maxStableSuspensionDampingRatio(travelForStabilityCheck, multiplierForStabilityCheck)*STABILITY_MARGIN) throw new IllegalArgumentException("invalid vehicle tuning");
   }
  }
  /** See the {@code suspensionDampingRatio} cross-field check in {@link #validate()} - a modest
   * safety factor applied to {@link #maxStableSuspensionDampingRatio} so the exact computed
   * boundary (a sustained non-decaying oscillation, confirmed by sweep - not actually stable
   * despite satisfying a naive non-strict {@code <=} comparison) is rejected too, not merely
   * values past it. */
  private static final double STABILITY_MARGIN = 0.995;
  /**
   * MINECRAFT-523 review (comments 53839/53840): the largest {@code suspensionDampingRatio}
   * that stays numerically stable at a given {@code suspensionTravel}, under {@code
   * Body.stepGround}'s own explicit-damping-term semi-implicit Euler integration at this
   * project's fixed {@link PhysicsBridge#DT}. Derived from the per-wheel natural frequency
   * {@code omega = sqrt(2*gravity/travel)} (mass/loadShare cancel out of the stability term
   * entirely - confirmed algebraically and by sweep) and the reviewer's own linearized stability
   * condition {@code (DT*omega)^2 + 4*ratio*DT*omega < 4}, solved for {@code ratio}:
   * {@code ratio < (4 - (DT*omega)^2) / (4*DT*omega)}. A {@code travel} small enough that even
   * {@code DT*omega >= 2} (so the spring term ALONE already exceeds the stability budget, before
   * any damping at all) returns {@code 0} - meaning no positive ratio is stable there; that is a
   * pre-existing property of {@code suspensionTravel}'s own already-validated {@code (0,1]} range
   * (an extremely small configured travel destabilizes even the unconfigured {@code 0.7} default),
   * not something introduced by this field, and this ticket does not attempt to fix it.
   * <p>Uses this project's own documented default gravity (the Sable {@code DimensionPhysics.
   * DEFAULT_GRAVITY} magnitude, 11 - see {@link PhysicsBridge#suspensionSpring}'s own doc) as a
   * conservative standard assumption, since gravity is a live per-dimension physics property,
   * never a {@code Tuning} field this validator can see - an unusually high dimension-configured
   * gravity could still destabilize a configuration this check accepts, exactly the same
   * pre-existing, out-of-{@code Tuning}-scope limitation every other hardcoded physics default in
   * this class (spring/damper calibration included) already carries.
   * <p>MINECRAFT-528: generalized to also take a configured {@code springMultiplier} - the per-
   * wheel spring rate {@code PhysicsBridge.suspensionSpringForShare}/{@code suspensionSpring}
   * compute is now scaled by that multiplier before the damper formula ever sees it, so the
   * natural frequency this method is already written against becomes {@code omega =
   * sqrt(2*gravity*springMultiplier/travel)} - still with mass/loadShare cancelling out of the
   * stability term entirely (the multiplier scales spring only, not mass or loadShare, so the
   * same cancellation argument applies unchanged). At {@code springMultiplier==1.0} this is
   * algebraically IDENTICAL to the original fixed-multiplier formula - the exact "preserved
   * calibrated defaults" equivalence this ticket requires for a vehicle that never configures
   * {@code suspensionSpringMultiplier}.
   */
  private static double maxStableSuspensionDampingRatio(double travel, double springMultiplier) {
   double gravity = 11.0;
   double omega = Math.sqrt(2*gravity*springMultiplier/travel);
   double dtOmega = PhysicsBridge.DT*omega;
   double headroom = 4 - dtOmega*dtOmega;
   if (headroom <= 0) return 0;
   return headroom/(4*dtOmega);
  }
  /**
   * Review finding (ticket 53518/MINECRAFT-505, self-review 53556/PR comment 6854012663):
   * "narrow the accepted part schema/docs to only supported attributes" - a {@code parts} entry
   * may ONLY set {@code driveForce}, {@code friction}, or (MINECRAFT-534) {@code
   * rollingResistance} (plus {@code extends}, which is structural, not itself a tuning field)
   * today, since those are the three fields
   * {@link io.github.brooswitminecraft.div.PhysicsBridge#enginePartDriveForce}/{@link
   * io.github.brooswitminecraft.div.PhysicsBridge#tirePartFriction}/{@link
   * io.github.brooswitminecraft.div.PhysicsBridge#tirePartRollingResistance} actually consume.
   * {@code friction} was added by MINECRAFT-529 (installed tire-specific grip overrides) - an
   * installed ground-device/tire part's own {@code friction} REPLACES (never multiplies) the
   * vehicle's resolved {@code friction} for that one wheel's contact only, same "complete
   * replacement, not a modifier" precedence {@code driveForce} already established for engine
   * parts; see {@code PhysicsBridge.tirePartFriction}'s own doc for the full composition.
   * {@code rollingResistance} was added by MINECRAFT-534 (installed-tire rolling-resistance
   * overrides), same replace-not-multiply precedence - see {@code
   * PhysicsBridge.tirePartRollingResistance}'s own doc. Every
   * other field - {@code brakeForce}/{@code maxSteerDegrees}/{@code lateralGripMultiplier}/
   * {@code waterDrag}/{@code propellerForce}/{@code massKg}/{@code hullFillFactor}/{@code
   * upperMassFraction}/every {@code selfRight*}/{@code enabled} - has no part-level consumer, so
   * a part entry that sets one would silently do nothing; this fails loudly at load/reload
   * instead, the same as an out-of-range value already does, rather than accepting a config an
   * operator would reasonably expect to have an effect. Called on the MERGED (post-{@code
   * extends}) entry, same as {@link #validate} already is for every {@code parts} entry in
   * {@link Overrides#readAndValidate} - a part inheriting an unsupported field from a template is
   * exactly as rejected as setting it directly; a part that inherits {@code friction} from a
   * template is, since MINECRAFT-529, exactly as ACCEPTED as setting it directly.
   */
  void validateSupportedForPart() {
   if (enabled != null) throw new IllegalArgumentException("part overrides do not support 'enabled' (vehicle-only concept)");
   if (massKg != null) throw new IllegalArgumentException("part overrides do not support 'massKg' (no part-level consumer yet)");
   if (suspensionTravel != null) throw new IllegalArgumentException("part overrides do not support 'suspensionTravel' (no part-level consumer yet)");
   // MINECRAFT-529: friction is now a supported part-level field (installed tire-specific grip
   // overrides) - see PhysicsBridge.tirePartFriction. Bounds are still exactly validate()'s
   // existing friction>0 && friction<=3 check above (run on this same merged entry by
   // Overrides.validateConfig right after this method), no new/different bound was introduced.
   // MINECRAFT-534: rollingResistance is likewise a supported part-level field (installed tire
   // rolling-resistance overrides) - see PhysicsBridge.tirePartRollingResistance. Bounds are
   // still exactly validate()'s own rollingResistance>0 && rollingResistance<=10 check above.
   if (brakeForce != null) throw new IllegalArgumentException("part overrides do not support 'brakeForce' (no part-level consumer yet)");
   if (maxSteerDegrees != null) throw new IllegalArgumentException("part overrides do not support 'maxSteerDegrees' (no part-level consumer yet)");
   if (hullFillFactor != null) throw new IllegalArgumentException("part overrides do not support 'hullFillFactor' (no part-level consumer yet)");
   if (waterDrag != null) throw new IllegalArgumentException("part overrides do not support 'waterDrag' (no part-level consumer yet)");
   if (propellerForce != null) throw new IllegalArgumentException("part overrides do not support 'propellerForce' (no part-level consumer yet)");
   if (angularWaterDrag != null) throw new IllegalArgumentException("part overrides do not support 'angularWaterDrag' (no part-level consumer yet)");
   if (selfRightStrength != null || selfRightDamping != null || selfRightMaxTorque != null
     || selfRightFadeStartDegrees != null || selfRightFadeEndDegrees != null)
    throw new IllegalArgumentException("part overrides do not support selfRight* fields (no part-level consumer yet)");
   if (upperMassFraction != null) throw new IllegalArgumentException("part overrides do not support 'upperMassFraction' (no part-level consumer yet)");
   if (lateralGripMultiplier != null) throw new IllegalArgumentException("part overrides do not support 'lateralGripMultiplier' (no part-level consumer yet)");
   if (antiRollStiffness != null) throw new IllegalArgumentException("part overrides do not support 'antiRollStiffness' (no part-level consumer yet)");
   if (stallAngleDegrees != null) throw new IllegalArgumentException("part overrides do not support 'stallAngleDegrees' (no part-level consumer yet)");
   if (liftSlope != null) throw new IllegalArgumentException("part overrides do not support 'liftSlope' (no part-level consumer yet)");
   if (controlSurfaceSlope != null) throw new IllegalArgumentException("part overrides do not support 'controlSurfaceSlope' (no part-level consumer yet)");
   if (cyclicLimitDegrees != null) throw new IllegalArgumentException("part overrides do not support 'cyclicLimitDegrees' (no part-level consumer yet)");
   if (cyclicAuthorityScale != null) throw new IllegalArgumentException("part overrides do not support 'cyclicAuthorityScale' (no part-level consumer yet)");
   if (yawAuthorityScale != null) throw new IllegalArgumentException("part overrides do not support 'yawAuthorityScale' (no part-level consumer yet)");
   if (brakeBiasFront != null) throw new IllegalArgumentException("part overrides do not support 'brakeBiasFront' (vehicle-only concept - a part has no own axle position)");
   if (driveBiasFront != null) throw new IllegalArgumentException("part overrides do not support 'driveBiasFront' (vehicle-only concept - a part has no own axle position)");
   if (suspensionDampingRatio != null) throw new IllegalArgumentException("part overrides do not support 'suspensionDampingRatio' (no part-level consumer yet)");
   if (suspensionSpringMultiplier != null) throw new IllegalArgumentException("part overrides do not support 'suspensionSpringMultiplier' (vehicle-only concept - no part-level consumer yet)");
   if (toeFrontDegrees != null) throw new IllegalArgumentException("part overrides do not support 'toeFrontDegrees' (vehicle-only concept - a part has no own axle position)");
   if (toeRearDegrees != null) throw new IllegalArgumentException("part overrides do not support 'toeRearDegrees' (vehicle-only concept - a part has no own axle position)");
   if (axleDriveForceSharing != null) throw new IllegalArgumentException("part overrides do not support 'axleDriveForceSharing' (vehicle-only concept - a part has no own axle position)");
  }
 }
 /** Documented fallback for every field, used only once every higher precedence level (operator
  * override, pack DIV metadata, IV-adapted native value, physically-derived value) has nothing
  * to say - the bottom of the chain, never the implicit result of an omitted field on its own.
  * Values/units/provenance unchanged from the shared numbers this ticket's physics work has
  * always used: driveForce 8000N, brakeForce 12000N, suspensionTravel 0.3m, friction mu=1.0
  * (dimensionless), maxSteerDegrees 30deg, hullFillFactor 0.5 (dimensionless), waterDrag 400
  * (solver-internal units), propellerForce 6000N. MINECRAFT-521: stallAngleDegrees/liftSlope/
  * controlSurfaceSlope fall back to {@link FlightForces}'s own DEFAULT_* constants - the exact
  * values that class previously hardcoded, so an unconfigured fixed-wing vehicle flies
  * byte-identically to before this ticket. MINECRAFT-522: cyclicLimitDegrees 20deg and
  * cyclicAuthorityScale/yawAuthorityScale 1.0 (unit scaling) reproduce RotorForces' own prior
  * hardcoded constant/behavior exactly - see that class's doc. */
 private static final Tuning FALLBACK = new Tuning();
 static {
  FALLBACK.suspensionTravel=0.3; FALLBACK.friction=1.0; FALLBACK.driveForce=8000.0; FALLBACK.brakeForce=12000.0;
  // MINECRAFT-534: 1.0 is a pure no-op multiplier against the ground-surface
  // rollingResistanceMultiplier - an unconfigured vehicle/tire-part rolls byte-identically to
  // before this ticket.
  FALLBACK.rollingResistance=1.0;
  FALLBACK.maxSteerDegrees=30.0; FALLBACK.hullFillFactor=0.5; FALLBACK.waterDrag=400.0; FALLBACK.propellerForce=6000.0;
  FALLBACK.upperMassFraction=0.5;
  FALLBACK.lateralGripMultiplier=1.0;
  FALLBACK.angularWaterDrag=0.0;
  FALLBACK.antiRollStiffness=0.0;
  FALLBACK.stallAngleDegrees=FlightForces.DEFAULT_STALL_ANGLE_DEGREES;
  FALLBACK.liftSlope=FlightForces.DEFAULT_LIFT_SLOPE;
  FALLBACK.controlSurfaceSlope=FlightForces.DEFAULT_CONTROL_SURFACE_SLOPE;
  FALLBACK.cyclicLimitDegrees=20.0;
  FALLBACK.cyclicAuthorityScale=1.0;
  FALLBACK.yawAuthorityScale=1.0;
  // MINECRAFT-523: the exact ratio PhysicsBridge.suspensionDamper/suspensionDamperForShare/
  // effectiveMassDamper previously hardcoded - an unconfigured vehicle settles byte-identically
  // to before this ticket.
  FALLBACK.suspensionDampingRatio=0.7;
  // MINECRAFT-528: 1.0 is a pure no-op multiplier against the calibrated spring rate - an
  // unconfigured vehicle's spring (and therefore ride-height equilibrium) stays byte-identical
  // to before this ticket.
  FALLBACK.suspensionSpringMultiplier=1.0;
 }
 private static Tuning copy(Tuning t) {
  Tuning c = new Tuning();
  c.extendsTemplate=t.extendsTemplate;
  c.enabled=t.enabled; c.massKg=t.massKg; c.suspensionTravel=t.suspensionTravel; c.friction=t.friction;
  c.rollingResistance=t.rollingResistance;
  c.driveForce=t.driveForce; c.brakeForce=t.brakeForce; c.maxSteerDegrees=t.maxSteerDegrees;
  c.hullFillFactor=t.hullFillFactor; c.waterDrag=t.waterDrag; c.propellerForce=t.propellerForce;
  c.selfRightStrength=t.selfRightStrength; c.selfRightDamping=t.selfRightDamping; c.selfRightMaxTorque=t.selfRightMaxTorque;
  c.selfRightFadeStartDegrees=t.selfRightFadeStartDegrees; c.selfRightFadeEndDegrees=t.selfRightFadeEndDegrees;
  c.upperMassFraction=t.upperMassFraction; c.lateralGripMultiplier=t.lateralGripMultiplier;
  c.angularWaterDrag=t.angularWaterDrag;
  c.antiRollStiffness=t.antiRollStiffness;
  c.stallAngleDegrees=t.stallAngleDegrees; c.liftSlope=t.liftSlope; c.controlSurfaceSlope=t.controlSurfaceSlope;
  c.cyclicLimitDegrees=t.cyclicLimitDegrees; c.cyclicAuthorityScale=t.cyclicAuthorityScale; c.yawAuthorityScale=t.yawAuthorityScale;
  c.brakeBiasFront=t.brakeBiasFront;
  c.driveBiasFront=t.driveBiasFront;
  c.suspensionDampingRatio=t.suspensionDampingRatio;
  c.suspensionSpringMultiplier=t.suspensionSpringMultiplier;
  c.toeFrontDegrees=t.toeFrontDegrees;
  c.toeRearDegrees=t.toeRearDegrees;
  c.axleDriveForceSharing=t.axleDriveForceSharing;
  return c;
 }
 /** Every field of {@code child} that is {@code null} falls through to {@code parent}'s own
  * (already-resolved) value - {@code child}'s own non-null fields always win. Used only to
  * fold one step of an {@code extends} chain; {@link #effective} is what walks the whole chain. */
 private static Tuning mergeOntoParent(Tuning child, Tuning parent) {
  Tuning m = new Tuning();
  m.enabled = child.enabled!=null?child.enabled:parent.enabled;
  m.massKg = child.massKg!=null?child.massKg:parent.massKg;
  m.suspensionTravel = child.suspensionTravel!=null?child.suspensionTravel:parent.suspensionTravel;
  m.friction = child.friction!=null?child.friction:parent.friction;
  m.rollingResistance = child.rollingResistance!=null?child.rollingResistance:parent.rollingResistance;
  m.driveForce = child.driveForce!=null?child.driveForce:parent.driveForce;
  m.brakeForce = child.brakeForce!=null?child.brakeForce:parent.brakeForce;
  m.maxSteerDegrees = child.maxSteerDegrees!=null?child.maxSteerDegrees:parent.maxSteerDegrees;
  m.hullFillFactor = child.hullFillFactor!=null?child.hullFillFactor:parent.hullFillFactor;
  m.waterDrag = child.waterDrag!=null?child.waterDrag:parent.waterDrag;
  m.propellerForce = child.propellerForce!=null?child.propellerForce:parent.propellerForce;
  m.selfRightStrength = child.selfRightStrength!=null?child.selfRightStrength:parent.selfRightStrength;
  m.selfRightDamping = child.selfRightDamping!=null?child.selfRightDamping:parent.selfRightDamping;
  m.selfRightMaxTorque = child.selfRightMaxTorque!=null?child.selfRightMaxTorque:parent.selfRightMaxTorque;
  m.selfRightFadeStartDegrees = child.selfRightFadeStartDegrees!=null?child.selfRightFadeStartDegrees:parent.selfRightFadeStartDegrees;
  m.selfRightFadeEndDegrees = child.selfRightFadeEndDegrees!=null?child.selfRightFadeEndDegrees:parent.selfRightFadeEndDegrees;
  m.upperMassFraction = child.upperMassFraction!=null?child.upperMassFraction:parent.upperMassFraction;
  m.lateralGripMultiplier = child.lateralGripMultiplier!=null?child.lateralGripMultiplier:parent.lateralGripMultiplier;
  m.angularWaterDrag = child.angularWaterDrag!=null?child.angularWaterDrag:parent.angularWaterDrag;
  m.antiRollStiffness = child.antiRollStiffness!=null?child.antiRollStiffness:parent.antiRollStiffness;
  m.stallAngleDegrees = child.stallAngleDegrees!=null?child.stallAngleDegrees:parent.stallAngleDegrees;
  m.liftSlope = child.liftSlope!=null?child.liftSlope:parent.liftSlope;
  m.controlSurfaceSlope = child.controlSurfaceSlope!=null?child.controlSurfaceSlope:parent.controlSurfaceSlope;
  m.cyclicLimitDegrees = child.cyclicLimitDegrees!=null?child.cyclicLimitDegrees:parent.cyclicLimitDegrees;
  m.cyclicAuthorityScale = child.cyclicAuthorityScale!=null?child.cyclicAuthorityScale:parent.cyclicAuthorityScale;
  m.yawAuthorityScale = child.yawAuthorityScale!=null?child.yawAuthorityScale:parent.yawAuthorityScale;
  m.brakeBiasFront = child.brakeBiasFront!=null?child.brakeBiasFront:parent.brakeBiasFront;
  m.driveBiasFront = child.driveBiasFront!=null?child.driveBiasFront:parent.driveBiasFront;
  m.suspensionDampingRatio = child.suspensionDampingRatio!=null?child.suspensionDampingRatio:parent.suspensionDampingRatio;
  m.suspensionSpringMultiplier = child.suspensionSpringMultiplier!=null?child.suspensionSpringMultiplier:parent.suspensionSpringMultiplier;
  m.toeFrontDegrees = child.toeFrontDegrees!=null?child.toeFrontDegrees:parent.toeFrontDegrees;
  m.toeRearDegrees = child.toeRearDegrees!=null?child.toeRearDegrees:parent.toeRearDegrees;
  m.axleDriveForceSharing = child.axleDriveForceSharing!=null?child.axleDriveForceSharing:parent.axleDriveForceSharing;
  return m;
 }
 /**
  * Resolves {@code entry}'s {@code extends} chain (ticket 53518/MINECRAFT-505), walking
  * {@link #templates} until a link with no {@code extends} is reached, folding each step with
  * {@link #mergeOntoParent} (closest entry always wins per field). {@code visiting} detects a
  * cycle (A extends B extends A) - thrown as {@link IllegalArgumentException}, the same failure
  * type every other malformed-config case in this class already uses, so {@link #load}/
  * {@link #reload} fail exactly the same way for a bad chain as for an out-of-range field.
  * {@code entry} itself is never looked up in {@link #templates} by this method - only names
  * {@code entry.extendsTemplate} may reference are; a template extending a vehicle/part key
  * (rather than another template) is therefore an "unknown extends template" failure, not
  * silently resolved - templates are their own namespace, deliberately not unioned with
  * {@link #vehicles}/{@link #parts} to avoid a vehicle entry accidentally becoming a valid
  * (and confusing) extension target for an unrelated vehicle or part. */
 private static Tuning effective(Tuning entry, Map<String,Tuning> templatesMap, Set<String> visiting) {
  if (entry == null || entry.extendsTemplate == null) return entry;
  String key = entry.extendsTemplate;
  if (!visiting.add(key)) throw new IllegalArgumentException("override 'extends' cycle detected at '"+key+"'");
  Tuning parent = templatesMap.get(key);
  if (parent == null) throw new IllegalArgumentException("unknown extends template '"+key+"'");
  Tuning resolvedParent = effective(parent, templatesMap, visiting);
  return mergeOntoParent(entry, resolvedParent);
 }
 /** {@link #effective(Tuning,Map,Set)} against the currently-ACTIVE {@link #templates} - what
  * every real {@link #resolved}/{@link #resolvedPart} call uses. {@link #readAndValidate} calls
  * the other overload directly against a CANDIDATE templates map instead, so a reload's
  * extends-chain validation never depends on (or mutates) the active one. */
 // Package-private, not private (ticket 53518 self-review) - lets OverridesTest directly exercise
 // the extends-merge this class's own readAndValidate uses for its post-merge coupled-field
 // validation, without needing a live FML config file to drive it through reload().
 static Tuning effective(Tuning entry) { return effective(entry, templates, new HashSet<>()); }
 /** Builds a {@code SelfRightingAssist.Config} from a resolved Tuning (ticket comment 53182/
  * 53188) - ALL FIVE selfRight* fields must be present together, or this returns {@code
  * SelfRightingAssist.OFF}. Deliberately all-or-nothing: a partially-configured assist has no
  * sensible meaning (what damping should apply with a strength but no configured maxTorque?),
  * so a vehicle must opt in with every field or get no assist at all, never a silent mix with
  * some other default. */
 public static io.github.brooswitminecraft.div.experimental.SelfRightingAssist.Config selfRightingConfig(Tuning resolved) {
  if (resolved.selfRightStrength==null || resolved.selfRightDamping==null || resolved.selfRightMaxTorque==null
    || resolved.selfRightFadeStartDegrees==null || resolved.selfRightFadeEndDegrees==null) {
   return io.github.brooswitminecraft.div.experimental.SelfRightingAssist.OFF;
  }
  return new io.github.brooswitminecraft.div.experimental.SelfRightingAssist.Config(
    resolved.selfRightStrength, resolved.selfRightDamping, resolved.selfRightMaxTorque,
    resolved.selfRightFadeStartDegrees, resolved.selfRightFadeEndDegrees);
 }
 /** Review (comment 53184): the shared {@link #FALLBACK} singleton must never be handed out
  * directly - every field on {@code Tuning} is public and mutable (required for Gson), so any
  * caller holding the literal shared instance could corrupt the fallback every other vehicle
  * resolves against. Returns a fresh, independently-mutable copy every call. Prefer
  * {@link #resolved} for any new call site - this is just its zero-override, zero-metadata,
  * zero-native-value case. */
 public static Tuning defaultTuning() { return copy(FALLBACK); }
 /** Ticket 53518 (MINECRAFT-505): {@code templates} is a third, separate namespace only {@code
  * extends} may reference (see {@link #effective}); {@code vehicles} is unchanged from before
  * this ticket; {@code parts} is new, keyed identically to {@code vehicles} but for a PART's own
  * identity (see {@link #resolvedPart}'s doc for the exact key shape) rather than a vehicle's. */
 // Package-private, not private (MINECRAFT-521 self-review) - lets OverridesTest construct a
 // Config directly for Overrides.apply(Supplier<Config>)'s own test seam (see its doc), without
 // needing Gson or a live FML config file to drive an invalid-reload-retains-last-known-good
 // regression through.
 record Config(Map<String,Tuning> templates, Map<String,Tuning> vehicles, Map<String,Tuning> parts) {}
 private static Map<String,Tuning> templates = Map.of();
 private static Map<String,Tuning> vehicles = Map.of();
 private static Map<String,Tuning> parts = Map.of();
 public static Tuning get(String key) { return vehicles.get(key); }
 /**
  * Review finding (ticket 53518/MINECRAFT-505, John's comment 53539): {@code enabled} must go
  * through the SAME {@code extends} chain {@link #resolved} already does, via {@link #effective},
  * not a raw {@code vehicles.get(key)} lookup - otherwise a vehicle entry that sets no fields of
  * its own beyond {@code extends: "some-disabled-template"} would resolve as opted-out via
  * {@link #resolved} but stay active via {@link #isOptedOut}, which is the one field whose
  * correctness actually gates activation (see {@code PhysicsBridge.active}'s own {@code
  * Overrides.isOptedOut(key)} call). {@code packMetadata}/{@code ivAdapted}/{@code
  * physicallyDerived} are deliberately NOT consulted here, matching this method's pre-existing
  * contract (operator config only) - only the {@code extends} resolution is new.
  */
 public static boolean isOptedOut(String key) { var t = effective(vehicles.get(key)); return t != null && Boolean.FALSE.equals(t.enabled); }
 /** The raw, unresolved (no {@code extends} applied) part override entry for {@code key}, if
  * any - mirrors {@link #get} for vehicles. See {@link #resolvedPart} for the key shape and the
  * actual precedence-resolved value callers should use instead of this raw entry. */
 public static Tuning getPart(String key) { return parts.get(key); }
 /**
  * Review finding (John's comment 53539/self-review 53542): {@link #resolvedPart} alone cannot
  * tell a caller "this part explicitly overrides field X" apart from "no part entry exists, so
  * this is the documented flat {@link #FALLBACK}" - both return the same non-null value for a
  * field like {@code driveForce}, which has a flat fallback. A production caller that wants
  * "part override wins over the vehicle's own resolved value, otherwise fall through to the
  * vehicle's value" (the deliberate precedence {@code PhysicsBridge}'s engine-drive-force
  * composition uses - see its own doc) needs the RAW, {@code extends}-resolved-but-NOT-fallback-
  * filled entry instead, so a still-null field unambiguously means "this part says nothing about
  * this field" - this is exactly that: {@code null} if {@code key} has no part entry at all,
  * otherwise the entry with its own {@code extends} chain folded in (same as {@link #resolved}
  * does for a vehicle), but with NO pack/native/physical/fallback layer applied.
  */
 public static Tuning effectivePartOverride(String key) { return effective(parts.get(key)); }
 private static Double firstNonNull(Double... values) { for (Double v : values) if (v != null) return v; return null; }
 private static Boolean firstNonNullBoolean(Boolean... values) { for (Boolean v : values) if (v != null) return v; return null; }
 /**
  * Per-field precedence resolution (ticket comment 53181/53184): explicit operator override
  * (this vehicle's {@code Overrides.get(key)} entry, if any) {@literal >} pack DIV metadata
  * {@literal >} an IV-adapted native value {@literal >} a physically-derived value {@literal >}
  * the documented {@link #FALLBACK}. Only the operator-override and fallback levels are wired to
  * a real source today; {@code packMetadata}/{@code ivAdapted}/{@code physicallyDerived} are
  * accepted now (each vehicle's own per-field value, or {@code null} if that source has nothing
  * to say for this field) so later work can plug in the pack-metadata file and native-value
  * adapters per field, independently of each other, without another signature change here.
  * EVERY field - including {@code enabled} and {@code massKg}, which an earlier version of this
  * method left checking only the operator override - goes through the full chain; {@code
  * massKg} and {@code enabled} simply have no flat {@link #FALLBACK} entry (massKg's own
  * fallback, the vehicle's own native mass, is vehicle-INSTANCE-dependent and lives in
  * PhysicsBridge, not a flat constant; {@code enabled} absent at every level means "use the
  * default classification-based eligibility", not forced on or off). Always returns a fully
  * non-null Tuning for every field that DOES have a fallback - callers never see a sparse one
  * for those; {@code enabled}/{@code massKg} can still legitimately come back {@code null}.
  *
  * <p>{@code packMetadata} and {@code ivAdapted}, if supplied, are validated (comment 53184)
  * before being used - a malformed upstream source must fail loudly here, the same as a
  * malformed operator-override entry already does in {@link #load}, not be silently trusted.
  */
 public static Tuning resolved(String key, Tuning packMetadata, Tuning ivAdapted, Tuning physicallyDerived) {
  return resolveFields(effective(vehicles.get(key)), packMetadata, ivAdapted, physicallyDerived);
 }
 /**
  * The part-level counterpart to {@link #resolved} (ticket 53518/MINECRAFT-505's "vehicle AND
  * part attributes" ask), same 4-level precedence chain and {@code extends} inheritance, over
  * {@link #parts} instead of {@link #vehicles}. {@code partKey} is this part's own identity,
  * deliberately NOT scoped to any one vehicle - {@code JSONPart}'s own {@code packID}/{@code
  * systemName} already uniquely identify a part DEFINITION the same way a vehicle's packID/
  * systemName do (see {@code PhysicsBridge.tuning}'s existing {@code packID+":"+systemName} key
  * shape, mirrored here): {@code partPackID+":"+partSystemName}. A part installed on several
  * different vehicles shares one override entry, same as a vehicle definition reused across
  * multiple spawned instances already shares one {@link #vehicles} entry - this is "vehicle
  * AND part attributes" alongside, not instead of, the existing vehicle-level resolution; a
  * caller wanting both layered together resolves each key independently and composes the two
  * results itself (no implicit vehicle-then-part stacking here, since not every part attribute
  * makes sense layered under its vehicle's - e.g. a part's own {@code driveForce} for an engine
  * part is a different physical quantity than a vehicle's overall {@code driveForce}).
  */
 public static Tuning resolvedPart(String partKey, Tuning packMetadata, Tuning ivAdapted, Tuning physicallyDerived) {
  return resolveFields(effective(parts.get(partKey)), packMetadata, ivAdapted, physicallyDerived);
 }
 /** {@link #resolvedPart} with no pack-metadata/IV-adapted/physically-derived source yet
  * available - mirrors {@link #resolved(String)}. */
 public static Tuning resolvedPart(String partKey) { return resolvedPart(partKey, null, null, null); }
 /** The actual per-field precedence chain (ticket comment 53181/53184), shared verbatim by
  * {@link #resolved} and {@link #resolvedPart} - {@code override} is whichever already-{@code
  * extends}-resolved entry the caller looked up (a vehicle's or a part's), never a raw map
  * lookup itself. */
 private static Tuning resolveFields(Tuning override, Tuning packMetadata, Tuning ivAdapted, Tuning physicallyDerived) {
  if (packMetadata != null) packMetadata.validate();
  if (ivAdapted != null) ivAdapted.validate();
  if (physicallyDerived != null) physicallyDerived.validate();
  Tuning result = new Tuning();
  result.enabled = firstNonNullBoolean(override!=null?override.enabled:null, packMetadata!=null?packMetadata.enabled:null, ivAdapted!=null?ivAdapted.enabled:null, physicallyDerived!=null?physicallyDerived.enabled:null);
  result.massKg = firstNonNull(override!=null?override.massKg:null, packMetadata!=null?packMetadata.massKg:null, ivAdapted!=null?ivAdapted.massKg:null, physicallyDerived!=null?physicallyDerived.massKg:null);
  result.suspensionTravel = firstNonNull(override!=null?override.suspensionTravel:null, packMetadata!=null?packMetadata.suspensionTravel:null, ivAdapted!=null?ivAdapted.suspensionTravel:null, physicallyDerived!=null?physicallyDerived.suspensionTravel:null, FALLBACK.suspensionTravel);
  result.friction = firstNonNull(override!=null?override.friction:null, packMetadata!=null?packMetadata.friction:null, ivAdapted!=null?ivAdapted.friction:null, physicallyDerived!=null?physicallyDerived.friction:null, FALLBACK.friction);
  result.rollingResistance = firstNonNull(override!=null?override.rollingResistance:null, packMetadata!=null?packMetadata.rollingResistance:null, ivAdapted!=null?ivAdapted.rollingResistance:null, physicallyDerived!=null?physicallyDerived.rollingResistance:null, FALLBACK.rollingResistance);
  result.driveForce = firstNonNull(override!=null?override.driveForce:null, packMetadata!=null?packMetadata.driveForce:null, ivAdapted!=null?ivAdapted.driveForce:null, physicallyDerived!=null?physicallyDerived.driveForce:null, FALLBACK.driveForce);
  result.brakeForce = firstNonNull(override!=null?override.brakeForce:null, packMetadata!=null?packMetadata.brakeForce:null, ivAdapted!=null?ivAdapted.brakeForce:null, physicallyDerived!=null?physicallyDerived.brakeForce:null, FALLBACK.brakeForce);
  result.maxSteerDegrees = firstNonNull(override!=null?override.maxSteerDegrees:null, packMetadata!=null?packMetadata.maxSteerDegrees:null, ivAdapted!=null?ivAdapted.maxSteerDegrees:null, physicallyDerived!=null?physicallyDerived.maxSteerDegrees:null, FALLBACK.maxSteerDegrees);
  result.hullFillFactor = firstNonNull(override!=null?override.hullFillFactor:null, packMetadata!=null?packMetadata.hullFillFactor:null, ivAdapted!=null?ivAdapted.hullFillFactor:null, physicallyDerived!=null?physicallyDerived.hullFillFactor:null, FALLBACK.hullFillFactor);
  result.waterDrag = firstNonNull(override!=null?override.waterDrag:null, packMetadata!=null?packMetadata.waterDrag:null, ivAdapted!=null?ivAdapted.waterDrag:null, physicallyDerived!=null?physicallyDerived.waterDrag:null, FALLBACK.waterDrag);
  result.propellerForce = firstNonNull(override!=null?override.propellerForce:null, packMetadata!=null?packMetadata.propellerForce:null, ivAdapted!=null?ivAdapted.propellerForce:null, physicallyDerived!=null?physicallyDerived.propellerForce:null, FALLBACK.propellerForce);
  result.angularWaterDrag = firstNonNull(override!=null?override.angularWaterDrag:null, packMetadata!=null?packMetadata.angularWaterDrag:null, ivAdapted!=null?ivAdapted.angularWaterDrag:null, physicallyDerived!=null?physicallyDerived.angularWaterDrag:null, FALLBACK.angularWaterDrag);
  // Self-righting (comment 53182/53188): no FALLBACK entry - stays null (meaning "assist off",
  // via selfRightingConfig's all-or-nothing check) unless some level actually sets it.
  result.selfRightStrength = firstNonNull(override!=null?override.selfRightStrength:null, packMetadata!=null?packMetadata.selfRightStrength:null, ivAdapted!=null?ivAdapted.selfRightStrength:null, physicallyDerived!=null?physicallyDerived.selfRightStrength:null);
  result.selfRightDamping = firstNonNull(override!=null?override.selfRightDamping:null, packMetadata!=null?packMetadata.selfRightDamping:null, ivAdapted!=null?ivAdapted.selfRightDamping:null, physicallyDerived!=null?physicallyDerived.selfRightDamping:null);
  result.selfRightMaxTorque = firstNonNull(override!=null?override.selfRightMaxTorque:null, packMetadata!=null?packMetadata.selfRightMaxTorque:null, ivAdapted!=null?ivAdapted.selfRightMaxTorque:null, physicallyDerived!=null?physicallyDerived.selfRightMaxTorque:null);
  result.selfRightFadeStartDegrees = firstNonNull(override!=null?override.selfRightFadeStartDegrees:null, packMetadata!=null?packMetadata.selfRightFadeStartDegrees:null, ivAdapted!=null?ivAdapted.selfRightFadeStartDegrees:null, physicallyDerived!=null?physicallyDerived.selfRightFadeStartDegrees:null);
  result.selfRightFadeEndDegrees = firstNonNull(override!=null?override.selfRightFadeEndDegrees:null, packMetadata!=null?packMetadata.selfRightFadeEndDegrees:null, ivAdapted!=null?ivAdapted.selfRightFadeEndDegrees:null, physicallyDerived!=null?physicallyDerived.selfRightFadeEndDegrees:null);
  result.upperMassFraction = firstNonNull(override!=null?override.upperMassFraction:null, packMetadata!=null?packMetadata.upperMassFraction:null, ivAdapted!=null?ivAdapted.upperMassFraction:null, physicallyDerived!=null?physicallyDerived.upperMassFraction:null, FALLBACK.upperMassFraction);
  result.lateralGripMultiplier = firstNonNull(override!=null?override.lateralGripMultiplier:null, packMetadata!=null?packMetadata.lateralGripMultiplier:null, ivAdapted!=null?ivAdapted.lateralGripMultiplier:null, physicallyDerived!=null?physicallyDerived.lateralGripMultiplier:null, FALLBACK.lateralGripMultiplier);
  result.antiRollStiffness = firstNonNull(override!=null?override.antiRollStiffness:null, packMetadata!=null?packMetadata.antiRollStiffness:null, ivAdapted!=null?ivAdapted.antiRollStiffness:null, physicallyDerived!=null?physicallyDerived.antiRollStiffness:null, FALLBACK.antiRollStiffness);
  result.stallAngleDegrees = firstNonNull(override!=null?override.stallAngleDegrees:null, packMetadata!=null?packMetadata.stallAngleDegrees:null, ivAdapted!=null?ivAdapted.stallAngleDegrees:null, physicallyDerived!=null?physicallyDerived.stallAngleDegrees:null, FALLBACK.stallAngleDegrees);
  result.liftSlope = firstNonNull(override!=null?override.liftSlope:null, packMetadata!=null?packMetadata.liftSlope:null, ivAdapted!=null?ivAdapted.liftSlope:null, physicallyDerived!=null?physicallyDerived.liftSlope:null, FALLBACK.liftSlope);
  result.controlSurfaceSlope = firstNonNull(override!=null?override.controlSurfaceSlope:null, packMetadata!=null?packMetadata.controlSurfaceSlope:null, ivAdapted!=null?ivAdapted.controlSurfaceSlope:null, physicallyDerived!=null?physicallyDerived.controlSurfaceSlope:null, FALLBACK.controlSurfaceSlope);
  result.cyclicLimitDegrees = firstNonNull(override!=null?override.cyclicLimitDegrees:null, packMetadata!=null?packMetadata.cyclicLimitDegrees:null, ivAdapted!=null?ivAdapted.cyclicLimitDegrees:null, physicallyDerived!=null?physicallyDerived.cyclicLimitDegrees:null, FALLBACK.cyclicLimitDegrees);
  result.cyclicAuthorityScale = firstNonNull(override!=null?override.cyclicAuthorityScale:null, packMetadata!=null?packMetadata.cyclicAuthorityScale:null, ivAdapted!=null?ivAdapted.cyclicAuthorityScale:null, physicallyDerived!=null?physicallyDerived.cyclicAuthorityScale:null, FALLBACK.cyclicAuthorityScale);
  result.yawAuthorityScale = firstNonNull(override!=null?override.yawAuthorityScale:null, packMetadata!=null?packMetadata.yawAuthorityScale:null, ivAdapted!=null?ivAdapted.yawAuthorityScale:null, physicallyDerived!=null?physicallyDerived.yawAuthorityScale:null, FALLBACK.yawAuthorityScale);
  // No FALLBACK entry (mirrors selfRight*/enabled/massKg above) - stays null unless some level
  // actually sets it, which is exactly the "no bias configured" signal BrakeBiasDistribution
  // needs to reproduce the pre-ticket even split.
  result.brakeBiasFront = firstNonNull(override!=null?override.brakeBiasFront:null, packMetadata!=null?packMetadata.brakeBiasFront:null, ivAdapted!=null?ivAdapted.brakeBiasFront:null, physicallyDerived!=null?physicallyDerived.brakeBiasFront:null);
  // MINECRAFT-524: same "no FALLBACK entry" shape as brakeBiasFront immediately above, for the
  // same reason - stays null (no bias configured) unless some level actually sets it, which is
  // exactly the signal DriveBiasDistribution needs to reproduce the pre-ticket even split.
  result.driveBiasFront = firstNonNull(override!=null?override.driveBiasFront:null, packMetadata!=null?packMetadata.driveBiasFront:null, ivAdapted!=null?ivAdapted.driveBiasFront:null, physicallyDerived!=null?physicallyDerived.driveBiasFront:null);
  result.suspensionDampingRatio = firstNonNull(override!=null?override.suspensionDampingRatio:null, packMetadata!=null?packMetadata.suspensionDampingRatio:null, ivAdapted!=null?ivAdapted.suspensionDampingRatio:null, physicallyDerived!=null?physicallyDerived.suspensionDampingRatio:null, FALLBACK.suspensionDampingRatio);
  result.suspensionSpringMultiplier = firstNonNull(override!=null?override.suspensionSpringMultiplier:null, packMetadata!=null?packMetadata.suspensionSpringMultiplier:null, ivAdapted!=null?ivAdapted.suspensionSpringMultiplier:null, physicallyDerived!=null?physicallyDerived.suspensionSpringMultiplier:null, FALLBACK.suspensionSpringMultiplier);
  // MINECRAFT-533: same "no FALLBACK entry" shape as brakeBiasFront/driveBiasFront above - stays
  // null (no toe configured for that axle) unless some level actually sets it, which is exactly
  // the signal AxleToeDistribution needs to reproduce the pre-ticket zero-offset behavior.
  result.toeFrontDegrees = firstNonNull(override!=null?override.toeFrontDegrees:null, packMetadata!=null?packMetadata.toeFrontDegrees:null, ivAdapted!=null?ivAdapted.toeFrontDegrees:null, physicallyDerived!=null?physicallyDerived.toeFrontDegrees:null);
  result.toeRearDegrees = firstNonNull(override!=null?override.toeRearDegrees:null, packMetadata!=null?packMetadata.toeRearDegrees:null, ivAdapted!=null?ivAdapted.toeRearDegrees:null, physicallyDerived!=null?physicallyDerived.toeRearDegrees:null);
  // MINECRAFT-532: no FALLBACK entry (mirrors brakeBiasFront/driveBiasFront above) - stays null
  // (treated as "off", see AxleDriveForceSharing's own doc) unless some level actually sets it.
  result.axleDriveForceSharing = firstNonNullBoolean(override!=null?override.axleDriveForceSharing:null, packMetadata!=null?packMetadata.axleDriveForceSharing:null, ivAdapted!=null?ivAdapted.axleDriveForceSharing:null, physicallyDerived!=null?physicallyDerived.axleDriveForceSharing:null);
  return result;
 }
 /** Builds a {@code TwoBodyChassis.Config} from a resolved Tuning's {@code upperMassFraction}
  * (ticket comment 53236/53240) - always resolves to a value (0.5 fallback), unlike
  * {@link #selfRightingConfig}'s all-or-nothing gate, since an even split is a sensible default
  * for every vehicle rather than "feature off". Validates against the vehicle's REAL total mass
  * (not just f in isolation) so a vehicle whose actual mass makes a configured fraction unsafe
  * (either half under the practical minimum) fails loudly here rather than silently producing a
  * near-massless half body downstream. */
 public static io.github.brooswitminecraft.div.experimental.TwoBodyChassis.Config twoBodyConfig(Tuning resolved, double totalMassKg) {
  var config = new io.github.brooswitminecraft.div.experimental.TwoBodyChassis.Config(resolved.upperMassFraction);
  config.validateAgainstTotalMass(totalMassKg);
  return config;
 }
 /** {@link #resolved} with no pack-metadata/IV-adapted/physically-derived source yet available -
  * exactly today's two-level chain (operator override, then the documented fallback), until
  * those three sources actually exist. */
 public static Tuning resolved(String key) { return resolved(key, null, null, null); }
 /** Test-only seam (ticket comment 53184: tests must exercise an actual explicit operator entry,
  * not only the unconfigured-key case) - {@link #load} is the only other writer, and it needs a
  * live FML config directory a plain unit test cannot provide. Package-private: only this
  * package's own tests may call it. Leaves {@link #templates}/{@link #parts} untouched - use
  * {@link #setForTest(Map,Map,Map)} to control all three at once (e.g. to test {@code extends}
  * or part resolution in isolation). */
 static void setForTest(Map<String,Tuning> v) { vehicles = v; }
 /** Full three-namespace test seam (ticket 53518/MINECRAFT-505) - see {@link #setForTest(Map)}. */
 static void setForTest(Map<String,Tuning> templatesForTest, Map<String,Tuning> vehiclesForTest, Map<String,Tuning> partsForTest) {
  templates = templatesForTest; vehicles = vehiclesForTest; parts = partsForTest;
 }
 // Deliberately NOT a static field (ticket 53518 self-review): Gson is only ever needed inside
 // load()/readAndValidate()'s own file I/O, and plain unit tests load this class without
 // com.google.gson on their classpath at all (the full Gson dependency is only present on the
 // real moddev/NeoForge runtime classpath) - a static field initializer runs eagerly the moment
 // ANY test merely references Overrides at all (even one that never touches JSON), which would
 // throw NoClassDefFoundError for every single plain-unit-test call site, not just load()'s own.
 private static Gson gson() { return new GsonBuilder().setPrettyPrinting().create(); }
 private static Path configPath() { return FMLPaths.CONFIGDIR.get().resolve("dynamic-immersive-vehicles.json"); }
 /**
  * Fully validates an already-parsed {@link Config} - every entry in {@code templates}/{@code
  * vehicles}/{@code parts} (field-range validation via {@link Tuning#validate}) AND every entry's
  * own {@code extends} chain (unknown target / cycle, via {@link #effective}, eagerly resolved
  * once per entry here so a bad chain fails at load/reload time, not on some later vehicle's
  * first tick - ticket 53518's "invalid/unknown values" requirement). Never mutates {@link
  * #templates}/{@link #vehicles}/{@link #parts} itself - {@link #load} and {@link #apply} each
  * decide separately what to do with a successful validation, and specifically how to react to a
  * failure (throw vs. keep the last-known-good maps and report it). The parse-independent half of
  * the original combined method (MINECRAFT-521 self-review) - factored out so {@link #apply} can
  * run this exact same validation against a test-supplied {@link Config} with no file I/O/Gson
  * involved at all; see {@link #readAndValidate} for what still wraps this with the actual parse.
  */
 private static Config validateConfig(Config config) {
  if (config == null || config.vehicles() == null) throw new IllegalArgumentException("missing vehicles map");
  Map<String,Tuning> templatesRead = config.templates() != null ? config.templates() : Map.of();
  Map<String,Tuning> vehiclesRead = config.vehicles();
  Map<String,Tuning> partsRead = config.parts() != null ? config.parts() : Map.of();
  templatesRead.values().forEach(Tuning::validate);
  vehiclesRead.values().forEach(Tuning::validate);
  partsRead.values().forEach(Tuning::validate);
  // Resolve every entry's extends chain now, against THIS candidate's own templatesRead (never
  // the currently-active static `templates` - the other effective(Tuning) overload) - a reload
  // must validate the new file entirely on its own terms before anything about it is trusted,
  // exactly like every other field check above. Nothing is swapped into the active statics
  // until this whole method returns successfully.
  //
  // Review finding (John's comment 53539): validating only each entry's own RAW fields (just
  // above) is not enough for a coupled check - selfRightFadeEndDegrees > selfRightFadeStartDegrees
  // is the one example today - since a child and its template can each independently pass their
  // own raw validate() while the MERGED (effective) combination is invalid (e.g. a child sets
  // only a fadeEndDegrees that is smaller than a fadeStartDegrees it inherits from its template -
  // each value is independently in-range, but the pairing isn't). So the merged result itself is
  // validated too, immediately after resolving the chain - a merge that is invalid fails loudly
  // at load/reload time, not silently downstream.
  for (var t : templatesRead.values()) effective(t, templatesRead, new HashSet<>()).validate();
  for (var t : vehiclesRead.values()) effective(t, templatesRead, new HashSet<>()).validate();
  for (var t : partsRead.values()) {
   var merged = effective(t, templatesRead, new HashSet<>());
   merged.validate();
   merged.validateSupportedForPart(); // review 53556: reject an inert (unsupported) part field loudly
  }
  return new Config(templatesRead, vehiclesRead, partsRead);
 }
 /** Parses {@code path} then runs it through {@link #validateConfig} - the checked-exception path
  * {@link #load} uses directly (a broken fresh-install file must fail loudly at startup). */
 private static Config readAndValidate(Path path) throws IOException {
  Config config = gson().fromJson(Files.readString(path), Config.class);
  return validateConfig(config);
 }
 /**
  * Test-only seam (MINECRAFT-521 self-review: the ticket requires an actual invalid-reload-
  * retains-last-known-good regression, not only {@code Tuning.validate()} throwing in isolation) -
  * the exact same validate-then-swap-or-reject logic {@link #reload} uses, against a caller-
  * supplied {@link Config} instead of one parsed from {@link #configPath}'s file, so a plain unit
  * test can install a known-good state, "reload" an invalid one, and assert the known-good state
  * was retained - with no live FML config directory or Gson needed at all. {@code configSupplier}
  * throwing (standing in for a real parse failure, the same substitution
  * {@code GroundSurfaceOverridesTest} already uses for its own loader) is treated exactly like a
  * validation failure: last-known-good, never a partial/garbage apply.
  */
 static ReloadResult apply(java.util.function.Supplier<Config> configSupplier) {
  try {
   Config config = validateConfig(configSupplier.get());
   templates = Map.copyOf(config.templates());
   vehicles = Map.copyOf(config.vehicles());
   parts = Map.copyOf(config.parts());
   return ReloadResult.ok(vehicles.size(), parts.size());
  } catch (RuntimeException ex) {
   // No configPath() call here (self-review fix): apply() is also the test seam, called directly
   // against a caller-supplied Config/Supplier with no live FML config directory at all -
   // configPath() itself throws (FMLPaths.CONFIGDIR.get() is null outside a real FML runtime) if
   // referenced from this generic message, masking the real validation failure with an unrelated
   // NPE. ex's own message already names what actually went wrong; reload()'s caller only ever
   // needs that, not a restated path it already knows (it's the one who called reload()).
   return ReloadResult.failed("Cannot reload: " + ex.getMessage());
  }
 }
 static void load() {
  Path path = configPath();
  try {
   if (!Files.exists(path)) {
    // mtsofficialpack:quad gets a real, ACTIVE self-right entry on a fresh install (ticket
    // comment 53182/53188's "explicit quad roll-assist" ask). This is a live, applied
    // configuration the moment a fresh install's quad is driven, not a commented-out or inert
    // example (comment 53189's correction: label it accurately as what it actually is) - an
    // operator who doesn't want it must explicitly remove/zero it. selfRightingConfig stays OFF
    // for every OTHER vehicle with no entry at all. Values are a deliberately gentle "cheat a
    // little" assist for this specific ~350kg ATV, not a generic default: strength/damping/
    // maxTorque chosen small relative to this vehicle's own inertiaBody.z (~44 kg*m^2, so even
    // the 600N*m maxTorque caps angular acceleration well under 15rad/s^2), fadeStart/fadeEnd
    // (10/50deg) keeps it acting only while basically upright. An existing installation's own
    // config file is NOT touched by this - only a genuinely fresh (non-existent) file gets it;
    // merging these same five fields into an existing operator config is a separate, manual
    // (or future tooling) step, not something this loader does automatically.
    var quadExample = new Tuning();
    quadExample.selfRightStrength=200.0; quadExample.selfRightDamping=80.0; quadExample.selfRightMaxTorque=600.0;
    quadExample.selfRightFadeStartDegrees=10.0; quadExample.selfRightFadeEndDegrees=50.0;
    Files.writeString(path, gson().toJson(new Config(Map.of(), Map.of("packid:vehicle", new Tuning(), "mtsofficialpack:quad", quadExample), Map.of())));
   }
   Config config = readAndValidate(path);
   templates = Map.copyOf(config.templates());
   vehicles = Map.copyOf(config.vehicles());
   parts = Map.copyOf(config.parts());
  } catch (IOException | RuntimeException ex) {
   throw new IllegalStateException("Cannot load " + path + ": " + ex.getMessage(), ex);
  }
 }
 /**
  * Non-throwing hot-reload (ticket 53518/MINECRAFT-505's core ask) - re-reads and fully
  * validates {@link #configPath}'s current contents via {@link #apply}, the same validation
  * {@link #load}'s own {@link #readAndValidate} runs, but on ANY failure (missing/malformed JSON, an out-of-range field, an
  * unknown {@code extends} target, a cycle) leaves {@link #templates}/{@link #vehicles}/
  * {@link #parts} completely untouched - last-known-good, per the ticket's explicit "retain
  * last-known-good on errors" requirement - and returns a {@link ReloadResult} describing the
  * failure instead of throwing, so a bad edit while the server is live cannot itself crash or
  * destabilize anything already running. Only ever SWAPS the three map references on success
  * (never mutates an existing map in place) - any {@code Tuning} object a caller is already
  * holding from a previous {@link #resolved}/{@link #resolvedPart} call is a value snapshot
  * unaffected by this swap either way; propagating a successful reload into already-active
  * vehicle instances is {@code PhysicsBridge.reloadOverrides}'s job, not this method's - this
  * method only ever changes what a FUTURE {@link #resolved}/{@link #resolvedPart} call sees.
  */
 public static ReloadResult reload() {
  // Delegates to the test-seam apply() above (MINECRAFT-521 self-review) - identical
  // validate-then-swap-or-reject behavior, just reached through the real file instead of a
  // caller-supplied Config. A checked IOException from the parse itself (e.g. the file was
  // deleted between calls) must still produce the same last-known-good failure apply() already
  // gives a RuntimeException - apply()'s Supplier<Config> contract has no checked-exception
  // channel, so it is converted to UncheckedIOException here and unwrapped into apply()'s normal
  // RuntimeException catch, not raised past this method.
  return apply(() -> {
   try {
    return gson().fromJson(Files.readString(configPath()), Config.class);
   } catch (IOException ex) {
    throw new java.io.UncheckedIOException(ex);
   }
  });
 }
 /** Outcome of a {@link #reload} call - {@code success} false means the previous
  * templates/vehicles/parts maps are UNCHANGED (last-known-good), never partially applied. */
 public record ReloadResult(boolean success, String message, int vehicleCount, int partCount) {
  static ReloadResult ok(int vehicleCount, int partCount) {
   return new ReloadResult(true, "reloaded "+vehicleCount+" vehicle override(s), "+partCount+" part override(s)", vehicleCount, partCount);
  }
  static ReloadResult failed(String message) { return new ReloadResult(false, message, -1, -1); }
 }
}
