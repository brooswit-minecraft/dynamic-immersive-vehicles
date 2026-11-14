package io.github.brooswitminecraft.div;
/**
 * MINECRAFT-524: per-wheel DRIVE budget share, derived from each wheel's own longitudinal
 * ({@code z}) anchor position - the same {@code z} {@link PhysicsBridge} already computes for
 * every wheel's suspension anchor (see {@link WheelLoadDistribution}, its load-share counterpart,
 * and {@link BrakeBiasDistribution}, its MINECRAFT-520 service-brake counterpart, which this class
 * mirrors field-for-field). {@link PhysicsBridge#stepGround} previously split the vehicle's whole
 * drive budget evenly across every wheel ({@code drive/wheels.size()}, no axle distinction at
 * all); this is the missing piece behind an operator-configurable front/rear drive-force split.
 *
 * <p>This is a geometric budget split across the wheels {@code PhysicsBridge} already treats as
 * drive-eligible (every non-spare ground device - see {@code PhysicsBridge#groundContacts}) - it
 * is NOT a differential (no per-wheel speed-sensing or torque-biasing under slip), a traction-
 * control system (no slip detection or per-wheel torque reduction), and NOT a gear-ratio
 * simulation (the vehicle's own configured drive-force budget is unchanged in total, just
 * redistributed by fixed geometric axle shares) - describing it as any of those three would
 * overclaim what this does.
 *
 * <p><b>Axle grouping is geometric, not steering-based.</b> The FRONT axle is whichever wheel(s)
 * sit at the maximum {@code z} (within {@link #AXLE_EPSILON}) - {@code z} is this engine's own
 * forward axis ({@code PhysicsBridge}'s {@code forward} is {@code q.transform(0,0,1)}), so the
 * wheel(s) at the greatest {@code z} really are the ones closest to the front, regardless of
 * whether they happen to steer - a rear-steering vehicle (forklift-style) is handled correctly by
 * this same rule, unlike a "steerable wheel == front" heuristic. Every wheel NOT in the front
 * group is treated as REAR for this split - a deliberate, explicit, conservative choice for a
 * genuine multi-axle layout (e.g. a tandem-rear 6-wheeler): rather than guess how to split bias
 * across more than two axle rows, every non-front row is simply lumped into one "rear" group
 * together, which is both well-defined for any wheel count and matches how a real front/rear
 * drive-bias knob is described even on a multi-axle vehicle.
 *
 * <p><b>Conservation.</b> Returned shares always sum to exactly 1.0 (biasFront + (1-biasFront)),
 * by construction - never a renormalized approximation - so the caller's whole drive-force budget
 * is preserved exactly regardless of wheel count or axle split. A wheel that is not grounded this
 * tick (no contact) simply does not receive its share this tick, the same conservative,
 * unredistributed loss every other per-wheel budget (suspension, brake) already accepts for a
 * missing contact - this class only computes the geometric share, it never looks at contact state.
 *
 * <p><b>Bounded/conservative fallback.</b> {@code null} {@code biasFront} (the field's own
 * documented "operator never configured this" state - see {@code Overrides.Tuning#driveBiasFront})
 * returns the plain even {@code 1/n} split for every wheel - byte-identical to the exact pre-
 * MINECRAFT-524 behavior, never merely close to it, so an installation with no bias configured is
 * completely unaffected. The same even-split fallback also covers every geometrically AMBIGUOUS
 * case: zero wheels (empty result), a single wheel (trivially both "front" and "the whole rear
 * axle" at once - no bias has anything to act on), and every wheel sharing the same {@code z}
 * (no distinguishable rear axle at all, e.g. a side-by-side-only layout, or a skid-steer track
 * pair at one axle row) - in every one of these, biasing one wheel against a nonexistent "other
 * axle" would be meaningless, not merely imprecise, so this falls back rather than fabricating an
 * axle split that doesn't exist. This fallback shape is identical to
 * {@link BrakeBiasDistribution#shares}'s own - see that method's doc for the full reasoning.
 */
public final class DriveBiasDistribution {
 private DriveBiasDistribution() {}
 /** Wheels within this {@code z} distance of the observed maximum are considered the same front
  * axle row - generous enough to absorb ordinary floating-point placement noise, tight enough
  * never to merge two genuinely distinct axles (real axle spacing is on the order of a block or
  * more; this is six orders of magnitude tighter). Same value as
  * {@link BrakeBiasDistribution#AXLE_EPSILON} - both classify the same physical axle geometry. */
 static final double AXLE_EPSILON = 1e-6;
 /** {@code z[i]}: wheel {@code i}'s own longitudinal anchor position (same frame as
  * {@code PhysicsBridge}'s per-wheel anchor {@code z} - see {@link WheelLoadDistribution#shares}
  * for the sibling lateral/longitudinal load-share computation this mirrors). {@code biasFront}
  * is the resolved {@code Overrides.Tuning#driveBiasFront} value - {@code null} means "unset",
  * {@code 0.0} sends the entire drive budget to the rear group, {@code 1.0} sends it entirely to
  * the front group, {@code 0.5} is the neutral/even axle split. Returns one share per wheel,
  * always finite, non-negative, and summing to exactly 1.0. */
 public static double[] shares(double[] z, Double biasFront) {
  int n = z.length;
  double[] evenShares = new double[n];
  if (n == 0) return evenShares;
  java.util.Arrays.fill(evenShares, 1.0 / n);
  if (biasFront == null) return evenShares;
  double maxZ = Double.NEGATIVE_INFINITY;
  for (double v : z) maxZ = Math.max(maxZ, v);
  boolean[] isFront = new boolean[n];
  int frontCount = 0;
  for (int i = 0; i < n; i++) {
   isFront[i] = z[i] >= maxZ - AXLE_EPSILON;
   if (isFront[i]) frontCount++;
  }
  int rearCount = n - frontCount;
  if (rearCount == 0) return evenShares; // no distinguishable rear axle - nothing to bias against
  double[] result = new double[n];
  double frontPerWheel = biasFront / frontCount;
  double rearPerWheel = (1.0 - biasFront) / rearCount;
  for (int i = 0; i < n; i++) result[i] = isFront[i] ? frontPerWheel : rearPerWheel;
  return result;
 }
 /**
  * {@link #shares} expressed as a RATIO (share*wheelCount) - what {@code PhysicsBridge} actually
  * multiplies into {@code driveForcePerWheel} (see {@code MultiContactFrictionSolver.
  * FrictionContact#driveShareRatio}). This is NOT simply {@code shares(z,biasFront)[i]*n}
  * computed by the caller: self-review (PR #24 comment 6871636687) found that round-trip
  * introduces ordinary floating-point error for the unset/fallback case at some wheel counts
  * (e.g. {@code (1.0/49)*49 == 0.9999999999999999}, not the exact {@code 1.0} the pre-ticket
  * {@code drive/wheels.size()} formula needs byte-for-byte) - the ticket's own "unset retains
  * exact accepted behavior" requirement means EXACT, not merely close. This method fills the
  * fallback (unset {@code biasFront}, or any degenerate layout {@link #shares} itself falls back
  * on) with the literal constant {@code 1.0} instead of deriving it through division then
  * remultiplication, so every wheel's ratio is byte-identically the true no-op in every case
  * {@link #shares} already documents as a fallback - never merely numerically close to it. */
 public static double[] shareRatios(double[] z, Double biasFront) {
  int n = z.length;
  double[] evenRatios = new double[n];
  if (n == 0) return evenRatios;
  java.util.Arrays.fill(evenRatios, 1.0);
  if (biasFront == null) return evenRatios;
  double maxZ = Double.NEGATIVE_INFINITY;
  for (double v : z) maxZ = Math.max(maxZ, v);
  boolean[] isFront = new boolean[n];
  int frontCount = 0;
  for (int i = 0; i < n; i++) {
   isFront[i] = z[i] >= maxZ - AXLE_EPSILON;
   if (isFront[i]) frontCount++;
  }
  int rearCount = n - frontCount;
  if (rearCount == 0) return evenRatios; // no distinguishable rear axle - nothing to bias against
  double[] result = new double[n];
  double frontRatio = biasFront * n / frontCount;
  double rearRatio = (1.0 - biasFront) * n / rearCount;
  for (int i = 0; i < n; i++) result[i] = isFront[i] ? frontRatio : rearRatio;
  return result;
 }
}
