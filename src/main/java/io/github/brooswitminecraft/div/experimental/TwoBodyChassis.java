package io.github.brooswitminecraft.div.experimental;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Pure geometry/mass/inertia math for the two-half (upper/lower) chassis design authorized in
 * ticket comment 53236/53240: "the body the way we used to, but split it in half vertically...
 * a weight distribution attribute that defaults to 0.5". Operates only on plain numbers/joml
 * types - no Minecraft/Sable runtime - same pattern as {@code HitchJoint}, so the mass/inertia
 * invariants can be checked without a live body.
 *
 * <p>Original full-box geometry: geometric center {@code G}, half-extents {@code (a,b,c)}
 * (so {@code b} is the HALF-height along Y), total mass {@code M}. Split at the horizontal plane
 * through {@code G} into an UPPER cuboid (center {@code G+(0,b/2,0)}, half-extents
 * {@code (a,b/2,c)}, mass {@code f*M}) and a LOWER cuboid (center {@code G-(0,b/2,0)}, same
 * half-extents, mass {@code (1-f)*M}) - preserving the original world/model geometry exactly, per
 * the ticket's explicit requirement; {@code f} (the upper mass fraction) is the only new knob.
 *
 * <p>Each half becomes its OWN independent Sable rigid body (a smaller solid box at its own mass/
 * half-extents) - Sable's own engine auto-computes each half's own correct solid-box inertia
 * internally (confirmed in this investigation's own history: {@code BoxPhysicsObject} exposes no
 * inertia-override API, and native box creation is mass/halfExtents/pose only - see
 * {@code ContactEffectiveMassNormalDirectionTest}'s doc and this ticket's comment history). This
 * is why this class does NOT hand DIV's own code an "aggregate assembly inertia" to apply forces
 * against as if the two halves were one rigid body (the ticket's explicit warning: "do not
 * pretend fixed constraints are infinitely rigid... verify momentum/torque response rather than
 * applying all forces to one half with aggregate inverse inertia by assumption") - a force on a
 * wheel belongs to whichever half it is rigidly part of, and THAT half's own (correctly, natively
 * computed) inertia governs its own direct rotational response; Sable's own joint/constraint
 * solver is what couples the two halves' motion, not a hand-rolled approximation of that coupling.
 * {@link #aggregateInertiaAboutAssemblyCom} exists ONLY to check the f=0.5 equivalence invariant
 * against the original single-body tensor - it is not meant to be fed into any per-impulse force
 * calculation.
 */
public final class TwoBodyChassis {
 private TwoBodyChassis() {}
 /** A practical nonzero minimum absolute mass (kg) for either half, to avoid near-massless
  * instability at an extreme fraction - ticket's explicit requirement ("practical nonzero minimum
  * mass/fraction guard"). Same order of magnitude as MTS's own documented minimum part mass
  * conventions elsewhere in this codebase (Overrides/PhysicsBridge use Math.max(50, ...) for
  * whole-vehicle mass) - chosen here as a fraction of that, since a HALF of a light vehicle can
  * legitimately be lighter than a whole one, but still must not approach zero. */
 public static final double MIN_HALF_MASS_KG = 5.0;
 /**
  * Upper mass fraction configuration - validated once, not re-validated per tick. {@code f} must
  * be finite and strictly between 0 and 1 (never silently clamped/inverted - the ticket's explicit
  * "do not silently invert semantics"), and both resulting half-masses (f*M and (1-f)*M) must meet
  * {@link #MIN_HALF_MASS_KG} for the vehicle's OWN total mass - this is why {@code validate} takes
  * the real total mass rather than being checked in isolation; the same {@code f} that is safe for
  * a 2000kg car can be unsafe for a 50kg minimum-mass vehicle.
  */
 public record Config(double upperMassFraction) {
  public Config {
   if (!Double.isFinite(upperMassFraction) || upperMassFraction <= 0 || upperMassFraction >= 1) {
    throw new IllegalArgumentException("upperMassFraction must be finite and strictly between 0 and 1, got " + upperMassFraction);
   }
  }
  public static final Config DEFAULT = new Config(0.5);
  /** @throws IllegalArgumentException if either resulting half-mass would fall below
   *  {@link #MIN_HALF_MASS_KG} for this vehicle's real total mass - checked against the ACTUAL
   *  mass, not assumed safe just because f itself is in (0,1). */
  public void validateAgainstTotalMass(double totalMassKg) {
   double upper = upperMassFraction * totalMassKg, lower = (1 - upperMassFraction) * totalMassKg;
   if (upper < MIN_HALF_MASS_KG || lower < MIN_HALF_MASS_KG) {
    throw new IllegalArgumentException("upperMassFraction=" + upperMassFraction + " on totalMass=" + totalMassKg
      + " would give upper=" + upper + "kg / lower=" + lower + "kg - below the " + MIN_HALF_MASS_KG + "kg minimum for either half");
   }
  }
 }
 public static double upperMassKg(double totalMassKg, Config config) { return config.upperMassFraction() * totalMassKg; }
 public static double lowerMassKg(double totalMassKg, Config config) { return (1 - config.upperMassFraction()) * totalMassKg; }
 /** Upper half's own geometric center, as an offset from the original full box's center G - a
  * constant, independent of mass fraction (ticket: "preserve original world/model geometry"). */
 public static Vector3d upperCenterOffsetFromG(double halfHeightB) { return new Vector3d(0, halfHeightB / 2, 0); }
 public static Vector3d lowerCenterOffsetFromG(double halfHeightB) { return new Vector3d(0, -halfHeightB / 2, 0); }
 /** Each half's own half-extents - same width/depth as the original box, half the height. */
 public static Vector3d halfExtentsOfEachHalf(Vector3dc originalHalfExtents) {
  return new Vector3d(originalHalfExtents.x(), originalHalfExtents.y() / 2, originalHalfExtents.z());
 }
 /**
  * Assembly center of mass, as an offset from G along Y only - ticket's own formula, re-derived
  * and confirmed here: comY - G_y = (f*M*(b/2) + (1-f)*M*(-b/2)) / M = b/2*(f-(1-f)) = b*(f-0.5).
  * At f=0.5 this is exactly zero (COM stays at G, as it must for an unmodified split). Positive f
  * above 0.5 (heavier upper half) moves the COM up, matching the ticket's own worked example
  * ("if its lower than 0.5 then the lower half will be heavier") - a LOWER f means a lighter upper
  * half and thus a COM shifted down (negative offset), consistent with this formula's sign.
  */
 public static double assemblyComOffsetFromG(double halfHeightB, Config config) {
  return halfHeightB * (config.upperMassFraction() - 0.5);
 }
 private static Vector3d ownInertia(double mass, double halfExtentX, double halfExtentY, double halfExtentZ) {
  return new Vector3d(
    mass / 3 * (halfExtentY * halfExtentY + halfExtentZ * halfExtentZ),
    mass / 3 * (halfExtentX * halfExtentX + halfExtentZ * halfExtentZ),
    mass / 3 * (halfExtentX * halfExtentX + halfExtentY * halfExtentY));
 }
 /** Each half's own solid-box inertia about ITS OWN center - exactly what Sable's own engine
  * will auto-compute internally for that half's independent rigid body (same formula shape this
  * codebase already uses everywhere else for a uniform solid box). This IS the quantity DIV's own
  * parallel friction-solver math should use for impulses applied to this specific half - not an
  * assembly aggregate. */
 public static Vector3d upperOwnInertia(double totalMassKg, double a, double halfHeightB, double c, Config config) {
  return ownInertia(upperMassKg(totalMassKg, config), a, halfHeightB / 2, c);
 }
 public static Vector3d lowerOwnInertia(double totalMassKg, double a, double halfHeightB, double c, Config config) {
  return ownInertia(lowerMassKg(totalMassKg, config), a, halfHeightB / 2, c);
 }
 /**
  * Aggregate inertia of BOTH halves about the ASSEMBLY COM (parallel-axis theorem, shift purely
  * along Y since both halves' centers sit directly above/below G on the Y axis only) - exists
  * ONLY to verify the f=0.5 equivalence invariant against the original single-body tensor
  * (checked explicitly in the paired test below), NOT fed into any production force-application
  * code (see this class's own doc). Derivation (dy = each half's own center Y minus assembly COM
  * Y): dy_upper=b*(1-f), dy_lower=-b*f; parallel-axis only affects X/Z moments (Y-shift doesn't
  * change the Y-axis moment itself) - Ixx_total = M/3*((b/2)^2+c^2) + M*b^2*f*(1-f),
  * Iyy_total = M/3*(a^2+c^2) (no f-dependence - a pure-Y split never changes yaw inertia),
  * Izz_total = M/3*(a^2+(b/2)^2) + M*b^2*f*(1-f). At f=0.5 every term collapses to exactly the
  * original full-box M/3*(sum of other two half-extents squared) - verified by hand before this
  * was written, and asserted in {@code TwoBodyChassisTest}.
  */
 public static Vector3d aggregateInertiaAboutAssemblyCom(double totalMassKg, double a, double halfHeightB, double c, Config config) {
  double f = config.upperMassFraction();
  double parallelAxisTerm = totalMassKg * halfHeightB * halfHeightB * f * (1 - f);
  double ixx = totalMassKg / 3 * (halfHeightB * halfHeightB / 4 + c * c) + parallelAxisTerm;
  double iyy = totalMassKg / 3 * (a * a + c * c);
  double izz = totalMassKg / 3 * (a * a + halfHeightB * halfHeightB / 4) + parallelAxisTerm;
  return new Vector3d(ixx, iyy, izz);
 }
 /**
  * Body-local fixed-joint anchor offsets, per review's correction (comment 53236: "existing
  * native joint anchors for boxes are body-local... for world joint point W use
  * inverse(qA)(W-PA)"). The natural shared anchor point is G itself (the midpoint of the two
  * halves' shared horizontal face, centered in X/Z) - since each half's own Sable pose position
  * IS its own geometric center (confirmed elsewhere in this codebase: BoxPhysicsObject's own
  * center-of-mass is always its pose position, never offset), and both halves share the SAME
  * world orientation at construction (neither has been independently rotated yet), this reduces
  * to a CONSTANT local offset - no live pose/orientation needed at all, unlike a hitch's anchors
  * (which connect two independently-oriented vehicles and must be recomputed from live poses):
  * upper's own center is directly ABOVE G by b/2, so G is at local (0,-b/2,0) in the upper body's
  * own frame; lower's own center is directly BELOW G by b/2, so G is at local (0,+b/2,0) in the
  * lower body's own frame.
  */
 public static Vector3d jointAnchorLocalInUpperBody(double halfHeightB) { return new Vector3d(0, -halfHeightB / 2, 0); }
 public static Vector3d jointAnchorLocalInLowerBody(double halfHeightB) { return new Vector3d(0, halfHeightB / 2, 0); }

 /**
  * World-frame correction to ADD to a lever arm/contact point already expressed relative to G
  * (the original single-body chassis center - what every wheel anchor/contact point in this
  * ticket's own force code is computed relative to, as {@code armOld = P - G} for a world
  * contact {@code P}) to get the SAME point expressed relative to the given half's own center
  * instead ({@code armNew = P - Cowner}) - what {@code applyImpulseAtPoint} and this codebase's
  * own hand-rolled impulse-sizing math both actually need (a lever arm relative to the body
  * receiving the impulse, not G).
  *
  * <p>Derivation (review 53277's correction of an earlier, wrong version of this method - see
  * below): {@code armNew = P - Cowner = (P - G) + (G - Cowner) = armOld + (G - Cowner)}, so the
  * correction to ADD is {@code (G - Cowner)}, in WORLD frame. From
  * {@code Cowner = G + orientation.transform(centerOffsetFromG(b))} (the construction formula
  * {@code TwoBodyLifecycle.create} itself uses), {@code (G - Cowner) =
  * -orientation.transform(centerOffsetFromG(b)) = orientation.transform(-centerOffsetFromG(b))}.
  * For the upper half, {@code upperCenterOffsetFromG(b) = (0,+b/2,0)}, so
  * {@code -upperCenterOffsetFromG(b) = (0,-b/2,0) = jointAnchorLocalInUpperBody(b)} exactly - NO
  * negation needed once expressed via {@code jointAnchorLocalInUpperBody}/
  * {@code jointAnchorLocalInLowerBody}, which already encode the needed sign. An EARLIER version
  * of this method added an extra negation (reasoning, wrongly, from "G + correction = Cowner" -
  * true, but the wrong invariant: that describes reconstructing a CENTER from G, the opposite
  * direction from correcting a LEVER ARM measured from G). The actual, correct invariant - what
  * this method must satisfy, and what its own test checks directly - is
  * {@code Cowner + (P - G + correction) == P} for an arbitrary contact {@code P}, not
  * {@code G + correction == Cowner}.
  *
  * <p>Independent of mass fraction {@code f} - this is pure geometry (the split plane's position
  * never depends on how mass is divided across it); f only affects each half's own mass/inertia,
  * not where its own center sits.
  */
 public static Vector3d worldLeverArmCorrection(Quaterniond orientation, double halfHeightB, boolean upperOwned) {
  Vector3d localCorrection = upperOwned ? jointAnchorLocalInUpperBody(halfHeightB) : jointAnchorLocalInLowerBody(halfHeightB);
  return orientation.transform(new Vector3d(localCorrection));
 }

 /**
  * World-frame correction to ADD to a lever arm/contact point already expressed relative to G to
  * get the SAME point expressed relative to the ASSEMBLY'S OWN center of mass instead - the
  * basis review 53280/53282/53285 requires for the friction solver's own IMPULSE-SIZING math
  * (anything that divides by an inertia tensor: {@code PhysicsBridge.effectiveMassDamper}, the
  * Phase 2 torque-accumulation loop, {@code MultiContactFrictionSolver.solveFriction}'s own mass/
  * inertia inputs). Paired with {@link #aggregateInertiaAboutAssemblyCom} and the vehicle's real
  * total mass - NOT either half's own isolated mass/inertia, since {@code
  * TwoBodyImpulseResponseProbe}'s own real CI evidence confirmed the joint rigidly couples both
  * halves into exactly one effective rigid body with THIS aggregate basis (measured angular
  * velocity change matched the aggregate prediction to ~0.0002 rad/s, versus a ~4-10x error
  * against treating either half as isolated).
  *
  * <p>Same derivation shape as {@link #worldLeverArmCorrection}: {@code armNew = P - Ccom =
  * armOld + (G - Ccom)}, and {@code Ccom = G + orientation.transform(0, assemblyComOffsetFromG,
  * 0)} (the assembly COM sits directly above/below G along local Y only - see
  * {@link #assemblyComOffsetFromG}'s own derivation), so
  * {@code (G - Ccom) = orientation.transform(0, -assemblyComOffsetFromG, 0)}.
  *
  * <p>NOT used for the actual native {@code applyImpulseAtPoint} call, which must stay targeted
  * at the real owning half's own local point via {@link #worldLeverArmCorrection} instead - this
  * method is for the solver's own PREDICTION math only, kept deliberately separate (review's
  * "aggregate COM prediction basis... actual owner-local point for native application").
  */
 public static Vector3d worldLeverArmCorrectionToAssemblyCom(Quaterniond orientation, double halfHeightB, Config config) {
  Vector3d localCorrection = new Vector3d(0, -assemblyComOffsetFromG(halfHeightB, config), 0);
  return orientation.transform(localCorrection);
 }

 /**
  * Force ownership (ticket comment 53261's "force ownership" ask, per 53236's own warning: a
  * wheel's impulse belongs to whichever half it is rigidly PART OF, not to some hand-rolled
  * aggregate). A wheel's anchor local Y (relative to G, the SAME {@code anchor.y} quantity
  * {@code Body.stepGround} already computes - {@code wheel.localOffset.sub(center)}) determines
  * which half it is physically attached to: at or above G it is part of the upper half-box's own
  * volume, below G it is part of the lower half's. For a typical ground vehicle every wheel sits
  * well below the chassis center, so in practice every wheel's impulse would go to the LOWER
  * half - that is the physically correct outcome (the "roof" of the vehicle does not touch the
  * ground directly; it only feels wheel forces indirectly, through the joint's own constraint
  * response), not an edge case to special-case away.
  */
 public static boolean isUpperHalf(double wheelAnchorLocalY) { return wheelAnchorLocalY >= 0; }

 /**
  * Pose reconstruction (ticket comment 53261's "pose reconstruction" ask): IV's own native code
  * needs ONE coherent vehicle position/orientation to render and sync, not two independent half-
  * body poses. Rather than averaging or picking a side ad hoc, this reconstructs G (the
  * original, single-body geometric center every other part of this codebase's geometry is
  * expressed relative to) from whichever half-body is chosen as the reference - the LOWER half is
  * the natural choice (it is where the wheels/suspension forces are actually applied, so it is
  * the half whose own motion most directly reflects ground contact) - using the SAME constant
  * local joint-anchor offset {@link #jointAnchorLocalInLowerBody} already derived for the joint
  * itself: G_world = lowerBodyPosition + lowerBodyOrientation.transform(jointAnchorLocalInLowerBody).
  * Orientation is reported as the reference body's own orientation directly - the joint locks all
  * 6 axes with an IDENTITY relative local frame (confirmed via the real native source, ticket
  * comment 53256), so the two halves' orientations coincide whenever the joint is actually
  * holding (barring real constraint solver slack, which is a live-runtime question this pure
  * function cannot itself detect).
  */
 public static Vector3d vehicleWorldPositionFromLowerBody(Vector3d lowerBodyPosition, Quaterniond lowerBodyOrientation, double halfHeightB) {
  return new Vector3d(lowerBodyOrientation.transform(jointAnchorLocalInLowerBody(halfHeightB))).add(lowerBodyPosition);
 }
}
