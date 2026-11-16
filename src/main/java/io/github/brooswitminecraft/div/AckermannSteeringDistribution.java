package io.github.brooswitminecraft.div;
/**
 * MINECRAFT-540: opt-in, bounded per-wheel Ackermann steering geometry for {@code
 * turnsWithSteer} wheels on a conventional two-axle layout - the inner and outer steering wheels
 * receive distinct mirrored angles that share one common turning center with the opposite
 * (non-steering, "pivot") axle, instead of {@code PhysicsBridge.stepGround}'s pre-ticket uniform
 * steer angle applied identically to every {@code turnsWithSteer} wheel.
 *
 * <p>Classic bicycle-model result: with signed wheelbase {@code L} (the steering axle's own
 * longitudinal position minus the pivot axle's) and the existing uniform steer angle {@code
 * delta}, the pivot-axle turn radius is {@code R = L / tan(delta)}; each steering wheel at
 * lateral offset {@code x} needs its own angle so its rolling direction stays perpendicular to
 * the line from that wheel to the shared turn center on the pivot-axle line, which solves to
 * {@code atan(L / (R - x))}, the plain (not {@code atan2}) arctangent - deliberately the
 * principal value in {@code (-90,90)} degrees, since a steering wheel's own angle must never
 * exceed a quarter turn; {@code atan2} would wrap a sign-crossing denominator into the obtuse
 * range instead, which is geometrically wrong here, not merely a different convention. A
 * denominator of exactly zero still degrades to the bounded {@code +-90} degree extreme
 * ({@code atan(+-Infinity)}), never {@code NaN}, because {@code L} is already guarded away from
 * zero below.
 *
 * <p><b>Ambiguous/unsupported layout fallback (returns {@code null}).</b> Skid-steer (already
 * handled by its own existing multiplier elsewhere, never this geometry), anything other than
 * exactly two {@code turnsWithSteer} wheels, those two wheels not straddling the centerline
 * (same-sign or zero {@code x} - a centered single-steer layout), a {@code turnsWithSteer} wheel
 * present in more than one distinct longitudinal band (multi-axle steer), any wheel that falls
 * into neither the steering band nor the single opposite band (a third/multi-axle layout), a
 * non-steering wheel sharing the steering band itself, or a near-zero wheelbase between those
 * two bands - in every such case the caller keeps its pre-ticket uniform steer angle unchanged,
 * byte-identical to before this ticket.
 *
 * <p>{@code delta == 0} (rudder centered) always returns all-zero angles directly, without
 * evaluating the geometry at all - avoids the signed-zero/signed-infinity edge in
 * {@code R = L / tan(0)} that would otherwise depend on the floating sign of a near-zero
 * wheelbase.
 */
public final class AckermannSteeringDistribution {
 private AckermannSteeringDistribution() {}
 /** Same tolerance and reasoning as {@link AxleToeDistribution#AXLE_EPSILON}. */
 static final double AXLE_EPSILON = 1e-6;

 /**
  * {@code x[i]}/{@code z[i]}: wheel {@code i}'s own lateral/longitudinal anchor position, same
  * frame as {@code PhysicsBridge}'s {@code loadShareX}/{@code loadShareZ}. {@code
  * turnsWithSteer[i]}: that wheel's own placement flag. {@code skidSteer}: the vehicle-wide flag
  * {@code PhysicsBridge.stepGround} already computes once per tick. {@code delta}: the existing
  * uniform steer angle (radians) {@code PhysicsBridge.stepGround} already derives from the
  * rudder input and {@code tuning.maxSteerDegrees} - unchanged by this class, only redistributed
  * per wheel.
  *
  * <p>Returns {@code null} when the layout is ambiguous or unsupported (see class doc) - the
  * caller must then keep using {@code delta} uniformly, exactly as before this ticket. A
  * non-null result has one radian angle per wheel; only the two {@code turnsWithSteer} indices
  * are meaningful, every other index is {@code 0.0} and unused by the caller (non-steering
  * wheels never read this array).
  */
 public static double[] steerRadians(double[] x, double[] z, boolean[] turnsWithSteer, boolean skidSteer, double delta) {
  int n = x.length;
  if (skidSteer) return null;
  if (delta == 0.0) return new double[n];
  double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
  for (double v : z) { minZ = Math.min(minZ, v); maxZ = Math.max(maxZ, v); }
  if (maxZ - minZ <= AXLE_EPSILON) return null; // no distinguishable second axle
  boolean[] high = new boolean[n];
  for (int i = 0; i < n; i++) {
   boolean inHigh = z[i] >= maxZ - AXLE_EPSILON;
   boolean inLow = z[i] <= minZ + AXLE_EPSILON;
   if (inHigh == inLow) return null; // a third/middle band - multi-axle layout
   high[i] = inHigh;
  }
  boolean steerInHigh = false, steerInLow = false;
  int steerCount = 0;
  for (int i = 0; i < n; i++) {
   if (!turnsWithSteer[i]) continue;
   steerCount++;
   if (high[i]) steerInHigh = true; else steerInLow = true;
  }
  if (steerCount != 2 || (steerInHigh && steerInLow)) return null;
  boolean steerBand = steerInHigh;
  double steerZSum = 0, pivotZSum = 0;
  int steerN = 0, pivotN = 0;
  double x0 = 0, x1 = 0;
  boolean sawFirst = false;
  for (int i = 0; i < n; i++) {
   if (high[i] == steerBand) {
    if (!turnsWithSteer[i]) return null; // non-steering wheel sharing the steering band
    steerZSum += z[i]; steerN++;
    if (!sawFirst) { x0 = x[i]; sawFirst = true; } else { x1 = x[i]; }
   } else {
    pivotZSum += z[i]; pivotN++;
   }
  }
  if (x0 == 0 || x1 == 0 || Math.signum(x0) == Math.signum(x1)) return null; // not a straddling pair
  double steerZ = steerZSum / steerN;
  double pivotZ = pivotZSum / pivotN;
  double L = steerZ - pivotZ;
  if (Math.abs(L) <= AXLE_EPSILON) return null; // degenerate wheelbase
  double R = L / Math.tan(delta);
  double[] result = new double[n];
  for (int i = 0; i < n; i++) {
   if (turnsWithSteer[i]) result[i] = Math.atan(L / (R - x[i]));
  }
  return result;
 }
}
