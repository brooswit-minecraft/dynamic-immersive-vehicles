package io.github.brooswitminecraft.div;
/**
 * MINECRAFT-539: per-wheel effective suspension spring-multiplier/damping-ratio, derived from
 * each wheel's own longitudinal ({@code z}) anchor position - the same {@code z} grouping
 * {@link BrakeBiasDistribution}/{@link DriveBiasDistribution}/{@link AxleToeDistribution} already
 * use for their own front/rear axle split (see {@link BrakeBiasDistribution}'s own doc for the
 * full "max z within {@link #AXLE_EPSILON} is the front axle, every other wheel is rear"
 * reasoning, mirrored verbatim here).
 *
 * <p><b>Inheritance, not a second default.</b> Unlike {@link AxleToeDistribution} (where an
 * unconfigured axle's toe is a true zero), an axle with no configured
 * {@code suspensionSpringMultiplierFront}/{@code Rear} or {@code suspensionDampingRatioFront}/
 * {@code Rear} INHERITS the vehicle's own already-resolved {@code suspensionSpringMultiplier}/
 * {@code suspensionDampingRatio} ({@code baseMultiplier}/{@code baseRatio} below) - never 1.0/
 * 0.7 directly, and never a second independently-defaulted value - so a vehicle that configures
 * only {@code suspensionSpringMultiplier} (vehicle-wide) and never touches any axle-specific
 * field behaves exactly as it did before this ticket, on every wheel.
 *
 * <p><b>Ambiguous axle-layout fallback.</b> When every wheel shares the same {@code z} (no
 * distinguishable rear axle - a single-axle/side-by-side-only layout, or a lone wheel), every
 * wheel gets the vehicle-wide {@code baseMultiplier}/{@code baseRatio} regardless of any
 * axle-specific field - there is no well-defined "which axle is this" question to answer, so
 * applying a front/rear-specific override to the one axle that exists would be fabricating a
 * distinction that isn't there, the same shape as {@link BrakeBiasDistribution}'s own
 * rear-count-zero fallback and {@link AxleToeDistribution}'s own ambiguous-layout fallback.
 */
public final class AxleSuspensionDistribution {
 private AxleSuspensionDistribution() {}
 /** Same tolerance and reasoning as {@link BrakeBiasDistribution#AXLE_EPSILON}. */
 static final double AXLE_EPSILON = 1e-6;
 /** One resolved spring multiplier per wheel. {@code z[i]} is wheel {@code i}'s own longitudinal
  * anchor position (same frame {@code PhysicsBridge} already computes as {@code loadShareZ}).
  * {@code front}/{@code rear} are the resolved {@code Overrides.Tuning#suspensionSpringMultiplierFront}/
  * {@code Rear} fields - {@code null} means "that axle's multiplier is unconfigured", in which
  * case that axle inherits {@code base} (the vehicle's own resolved
  * {@code suspensionSpringMultiplier}). Always finite and, for any input {@code base}/{@code
  * front}/{@code rear} already accepted by {@code Overrides.Tuning#validate}, within the same
  * bounds that validation already enforces. */
 public static double[] springMultipliers(double[] z, double base, Double front, Double rear) {
  return resolve(z, base, front, rear);
 }
 /** Same contract as {@link #springMultipliers}, for {@code Overrides.Tuning#suspensionDampingRatioFront}/
  * {@code Rear} overriding {@code suspensionDampingRatio} instead. */
 public static double[] dampingRatios(double[] z, double base, Double front, Double rear) {
  return resolve(z, base, front, rear);
 }
 private static double[] resolve(double[] z, double base, Double front, Double rear) {
  int n = z.length;
  double[] result = new double[n];
  if (n == 0) return result;
  if (front == null && rear == null) { java.util.Arrays.fill(result, base); return result; }
  double maxZ = Double.NEGATIVE_INFINITY;
  for (double v : z) maxZ = Math.max(maxZ, v);
  boolean[] isFront = new boolean[n];
  int frontCount = 0;
  for (int i = 0; i < n; i++) {
   isFront[i] = z[i] >= maxZ - AXLE_EPSILON;
   if (isFront[i]) frontCount++;
  }
  int rearCount = n - frontCount;
  if (rearCount == 0) { java.util.Arrays.fill(result, base); return result; } // ambiguous layout
  double frontValue = front != null ? front : base;
  double rearValue = rear != null ? rear : base;
  for (int i = 0; i < n; i++) result[i] = isFront[i] ? frontValue : rearValue;
  return result;
 }
}
