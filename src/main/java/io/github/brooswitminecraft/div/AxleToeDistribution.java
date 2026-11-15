package io.github.brooswitminecraft.div;
/**
 * MINECRAFT-533: per-wheel static toe alignment angle (radians), derived from each wheel's own
 * lateral ({@code x}) and longitudinal ({@code z}) anchor position - the same {@code z} grouping
 * {@link BrakeBiasDistribution}/{@link DriveBiasDistribution} already use for their own front/
 * rear axle split (see {@link BrakeBiasDistribution}'s own doc for the full "max z within
 * {@link #AXLE_EPSILON} is the front axle, every other wheel is rear" reasoning, mirrored
 * verbatim here), plus the {@code x} sign that already distinguishes left/right for
 * {@code PhysicsBridge}'s own skid-steer multiplier.
 *
 * <p><b>Sign convention.</b> Every wheel's own rolling-forward direction points the same way
 * (the vehicle's {@code +z}) when going straight, regardless of which axle it sits on - so one
 * formula, {@code -signum(x) * toeRadians}, mirrors correctly for BOTH axles: a positive
 * {@code toeFrontDegrees}/{@code toeRearDegrees} ("toe-in") steers the right-side wheel
 * ({@code x>0}) slightly toward the centerline (negative offset) and the left-side wheel
 * ({@code x<0}) slightly toward the centerline too (positive offset) - converging, never
 * diverging, at a positive setting. A wheel sitting exactly on the centerline ({@code x==0},
 * {@code signum==0}) gets no toe at all - there is no "other side" to mirror against, so this is
 * the same conservative "nothing meaningful to apply" choice {@link #AXLE_EPSILON}'s own
 * ambiguous-layout fallback makes.
 *
 * <p><b>Ambiguous axle-layout fallback.</b> When every wheel shares the same {@code z} (no
 * distinguishable rear axle - a single-axle/side-by-side-only layout, or a lone wheel), this
 * returns all zeros regardless of configured toe: there is no well-defined "which axle is this"
 * question to answer, so applying either configured value to the one axle that exists would be
 * fabricating a distinction that isn't there - the same shape as {@link BrakeBiasDistribution}'s
 * own rear-count-zero fallback.
 */
public final class AxleToeDistribution {
 private AxleToeDistribution() {}
 /** Same tolerance and reasoning as {@link BrakeBiasDistribution#AXLE_EPSILON}. */
 static final double AXLE_EPSILON = 1e-6;
 /** {@code x[i]}/{@code z[i]}: wheel {@code i}'s own lateral/longitudinal anchor position (same
  * frame {@code PhysicsBridge} already computes as {@code loadShareX}/{@code loadShareZ}).
  * {@code toeFrontDegrees}/{@code toeRearDegrees} are the resolved {@code Overrides.Tuning}
  * fields - {@code null} means "that axle's toe is unconfigured" (0 radians for that axle alone,
  * not a layout fallback). Returns one signed radian offset per wheel, always finite. */
 public static double[] toeRadians(double[] x, double[] z, Double toeFrontDegrees, Double toeRearDegrees) {
  int n = x.length;
  double[] zero = new double[n];
  if (n == 0) return zero;
  if (toeFrontDegrees == null && toeRearDegrees == null) return zero;
  double maxZ = Double.NEGATIVE_INFINITY;
  for (double v : z) maxZ = Math.max(maxZ, v);
  boolean[] isFront = new boolean[n];
  int frontCount = 0;
  for (int i = 0; i < n; i++) {
   isFront[i] = z[i] >= maxZ - AXLE_EPSILON;
   if (isFront[i]) frontCount++;
  }
  int rearCount = n - frontCount;
  if (rearCount == 0) return zero; // no distinguishable rear axle - ambiguous layout, no toe applied
  double frontRadians = toeFrontDegrees != null ? Math.toRadians(toeFrontDegrees) : 0.0;
  double rearRadians = toeRearDegrees != null ? Math.toRadians(toeRearDegrees) : 0.0;
  double[] result = new double[n];
  for (int i = 0; i < n; i++) {
   double magnitude = isFront[i] ? frontRadians : rearRadians;
   result[i] = -Math.signum(x[i]) * magnitude;
  }
  return result;
 }
}
