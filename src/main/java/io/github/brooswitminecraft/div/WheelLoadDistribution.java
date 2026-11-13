package io.github.brooswitminecraft.div;
/**
 * MINECRAFT-506: per-wheel STATIC load share, derived from each wheel's horizontal anchor
 * position relative to the chassis's own center/COM reference (exactly the same {@code x,z}
 * {@link PhysicsBridge} already computes for every wheel's suspension anchor - no new input, no
 * per-vehicle-ID table). This is the missing piece behind the uniform {@code mass/wheelCount}
 * calibration {@link PhysicsBridge#suspensionSpring}/{@link PhysicsBridge#suspensionDamper} used
 * for every wheel: a real vehicle rarely carries its weight evenly across every wheel (an
 * engine-forward sedan's front axle, a rear-heavy truck's rear axle), and {@link PhysicsBridge}
 * already supports arbitrary wheel counts/placements that a flat {@code 1/wheelCount} split
 * cannot represent honestly.
 *
 * <p><b>The model (ordinary statics, not a per-car table).</b> Treat the chassis as a rigid plank
 * resting on {@code n} vertical springs of EQUAL rate, one per wheel, at that wheel's own
 * {@code (x,z)} anchor relative to the chassis COM. At equilibrium the plank may carry a small
 * pitch/roll tilt so that (1) the springs' combined force equals the total weight and (2) the
 * combined moment about the COM is exactly zero on both horizontal axes - the same "sum of
 * moments about COM = 0, sum of vertical reactions = total weight" statics the ticket's own
 * inventory names. For equal-rate springs the per-wheel reaction is an affine function of its own
 * position, {@code F_i = a + b*z_i + c*x_i}; solving the resulting 3x3 linear system for
 * {@code (a,b,c)} against unit total weight gives each wheel's SHARE directly (shares are linear
 * in the total weight, so solving at weight=1 is exactly the per-wheel fraction of whatever the
 * real total weight actually is).
 *
 * <p>This is a genuine 2D (longitudinal AND lateral) statics solve, not a 1D front/rear-only
 * approximation oversold as more than it is - it is still a SIMPLIFICATION of a real suspension
 * (real springs are rarely exactly equal-rate, and a real chassis is not a perfectly rigid plank),
 * but it is geometrically honest: it reduces to EXACTLY {@code 1/n} for the common symmetric case
 * (every wheel mirrored about the COM on both axes - see {@link #shares} test coverage), and
 * shifts load toward whichever wheel/axle sits closer to the COM for an asymmetric layout, which
 * matches real vehicle behavior (the corner nearer the center of mass carries more of the static
 * load, the corner farther away carries less).
 *
 * <p><b>Bounded validation (no NaN/negative/divide-by-zero on degenerate geometry).</b> Zero
 * wheels returns an empty array; one wheel always returns {@code [1.0]}; a singular system (e.g.
 * every wheel collinear in a way that leaves the 3x3 matrix non-invertible, or all wheels at the
 * same point) falls back to the even {@code 1/n} split for every wheel, rather than dividing by a
 * near-zero determinant. A solved share that comes out negative or non-finite (a geometrically
 * implausible layout for this rigid-equal-spring model) also falls back to the even split for
 * EVERY wheel, not a per-wheel clamp - the same "cap the TRANSFER, never independently clamp one
 * wheel's result" lesson this ticket's own DV-reference triage names for anti-roll: clamping one
 * wheel's share without rebalancing the rest would manufacture load out of nowhere (shares would
 * no longer sum to 1.0). Every returned array always sums to exactly 1.0 (defensively
 * renormalized after the solve, since floating-point division can drift a few ULPs away from
 * exact).
 */
public final class WheelLoadDistribution {
 private WheelLoadDistribution() {}
 /** {@code x[i]}/{@code z[i]}: wheel {@code i}'s horizontal anchor position relative to the
  * chassis center/COM reference (same frame as {@code PhysicsBridge}'s own per-wheel anchor
  * {@code x}/{@code z}). Returns one share per wheel, always finite, non-negative, and summing to
  * exactly 1.0. {@code x} and {@code z} must be the same length; a length mismatch is a caller
  * bug, not a runtime-data case this validates. */
 public static double[] shares(double[] x, double[] z) {
  int n = x.length;
  if (n == 0) return new double[0];
  double even = 1.0 / n;
  double[] evenShares = new double[n];
  java.util.Arrays.fill(evenShares, even);
  if (n == 1) return new double[]{1.0};
  double sumX = 0, sumZ = 0, sumXX = 0, sumZZ = 0, sumXZ = 0;
  for (int i = 0; i < n; i++) {
   sumX += x[i]; sumZ += z[i];
   sumXX += x[i] * x[i]; sumZZ += z[i] * z[i]; sumXZ += x[i] * z[i];
  }
  // M * (a,b,c)^T = (1,0,0)^T at unit total weight - F_i = a + b*z_i + c*x_i is then directly
  // wheel i's own share of the real total weight (shares are linear in weight).
  double m00 = n,    m01 = sumZ,  m02 = sumX;
  double m10 = sumZ, m11 = sumZZ, m12 = sumXZ;
  double m20 = sumX, m21 = sumXZ, m22 = sumXX;
  double det = m00 * (m11 * m22 - m12 * m21) - m01 * (m10 * m22 - m12 * m20) + m02 * (m10 * m21 - m11 * m20);
  if (!Double.isFinite(det) || Math.abs(det) < 1e-9) return evenShares;
  double a = (m11 * m22 - m12 * m21) / det;
  double b = -(m10 * m22 - m12 * m20) / det;
  double c = (m10 * m21 - m11 * m20) / det;
  double[] result = new double[n];
  double sum = 0;
  for (int i = 0; i < n; i++) {
   double s = a + b * z[i] + c * x[i];
   if (!Double.isFinite(s) || s < 0) return evenShares;
   result[i] = s;
   sum += s;
  }
  if (!Double.isFinite(sum) || sum <= 0) return evenShares;
  for (int i = 0; i < n; i++) result[i] /= sum;
  return result;
 }
}
