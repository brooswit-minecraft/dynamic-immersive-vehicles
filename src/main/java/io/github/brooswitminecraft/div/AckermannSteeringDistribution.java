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
 *
 * <p><b>Full-lock curvature limit (Wroosbit review 54164; John's follow-up 54166/54173 on an
 * earlier revision of this fix that clipped each wheel's angle independently).</b> The raw
 * {@code atan(L / (R - x))} is only the correct Ackermann angle while the steering wheel's
 * lateral offset stays inside the turn-center radius ({@code |x| < |R|}); once a tight enough
 * wheelbase/track/lock combination pushes {@code x} past {@code R} (still a layout this class
 * otherwise accepts - not one of the ambiguous-layout fallbacks above), the raw denominator
 * {@code R - x} crosses zero and {@code atan} folds the result back across the pivot into the
 * WRONG sign and an angle smaller than the vehicle's own configured full lock, not larger -
 * silently reversing the ticket's own "preserve existing steer signs" requirement instead of
 * merely looking large.
 *
 * <p>The fix is NOT to clip each wheel's angle independently - two wheels clipped to the same
 * bound by two DIFFERENT amounts stop sharing a common turning center at all (John's catch:
 * independent per-wheel clipping preserves signs/bounds but silently abandons the very geometric
 * contract this ticket exists to provide). Instead, when either wheel's raw angle would break
 * the sign or the {@code maxLockRadians} bound, the shared curvature itself is relaxed: a NEW
 * turn-center position {@code R'} is solved for so that the inner wheel (the one geometrically
 * closest to the raw turn center, {@code argmin |R - x|}) lands EXACTLY at {@code
 * sign(delta) * maxLockRadians}, and the outer wheel's angle is then recomputed from that SAME
 * {@code R'} - both wheels still share one turning center, just a less sharp one than the raw
 * (unbounded) geometry would have used. This is exactly the pre-ticket uniform angle's own
 * behavior at full lock (saturate, don't reverse), extended to a shared-curvature model instead
 * of an independent per-wheel one. If the relaxed outer angle still cannot satisfy sign/bound
 * (not observed for any accepted layout, but checked rather than assumed), this falls back to
 * {@code null} - the explicit "geometry cannot satisfy the contract" escape John's follow-up
 * named, never a silently wrong angle. This never fires for ordinary steering geometry/lock
 * combinations (see {@code AckermannSteeringDistributionTest}'s own non-clamped cases) - only at
 * the accepted layout's own extremes.
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
  * per wheel. {@code maxLockRadians}: the same {@code tuning.maxSteerDegrees} bound, in radians,
  * ALWAYS positive - the signed bound the shared-curvature limit (see class doc) keeps the
  * inner wheel's own angle within.
  *
  * <p>Returns {@code null} when the layout is ambiguous or unsupported (see class doc) - the
  * caller must then keep using {@code delta} uniformly, exactly as before this ticket. A
  * non-null result has one radian angle per wheel; only the two {@code turnsWithSteer} indices
  * are meaningful, every other index is {@code 0.0} and unused by the caller (non-steering
  * wheels never read this array).
  */
 public static double[] steerRadians(double[] x, double[] z, boolean[] turnsWithSteer, boolean skidSteer, double delta, double maxLockRadians) {
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
  int idx0 = -1, idx1 = -1;
  for (int i = 0; i < n; i++) {
   if (high[i] == steerBand) {
    if (!turnsWithSteer[i]) return null; // non-steering wheel sharing the steering band
    steerZSum += z[i]; steerN++;
    if (idx0 == -1) idx0 = i; else idx1 = i;
   } else {
    pivotZSum += z[i]; pivotN++;
   }
  }
  double x0 = x[idx0], x1 = x[idx1];
  if (x0 == 0 || x1 == 0 || Math.signum(x0) == Math.signum(x1)) return null; // not a straddling pair
  double steerZ = steerZSum / steerN;
  double pivotZ = pivotZSum / pivotN;
  double L = steerZ - pivotZ;
  if (Math.abs(L) <= AXLE_EPSILON) return null; // degenerate wheelbase
  double R = L / Math.tan(delta);
  double deltaSign = Math.signum(delta);
  double angle0 = Math.atan(L / (R - x0));
  double angle1 = Math.atan(L / (R - x1));
  boolean breaks0 = Math.signum(angle0) != deltaSign || Math.abs(angle0) > maxLockRadians;
  boolean breaks1 = Math.signum(angle1) != deltaSign || Math.abs(angle1) > maxLockRadians;
  if (breaks0 || breaks1) {
   // Shared-curvature limit (see class doc): relax R toward whichever wheel is geometrically
   // closest to the raw turn center (the true inner wheel, which hits the bound first) so it
   // lands EXACTLY at the signed full lock, then recompute the OTHER wheel from that SAME
   // relaxed R' - both wheels still share one turning center.
   boolean innerIs0 = Math.abs(R - x0) <= Math.abs(R - x1);
   double xInner = innerIs0 ? x0 : x1;
   double target = deltaSign * maxLockRadians;
   double tanTarget = Math.tan(target);
   if (tanTarget == 0.0) return null; // maxLockRadians itself is zero - no usable lock at all
   double rPrime = xInner + L / tanTarget;
   angle0 = Math.atan(L / (rPrime - x0));
   angle1 = Math.atan(L / (rPrime - x1));
   boolean stillBreaks0 = Math.signum(angle0) != deltaSign || Math.abs(angle0) > maxLockRadians + AXLE_EPSILON;
   boolean stillBreaks1 = Math.signum(angle1) != deltaSign || Math.abs(angle1) > maxLockRadians + AXLE_EPSILON;
   if (stillBreaks0 || stillBreaks1) return null; // geometry cannot satisfy the contract at all
  }
  double[] result = new double[n];
  result[idx0] = angle0;
  result[idx1] = angle1;
  return result;
 }
}
