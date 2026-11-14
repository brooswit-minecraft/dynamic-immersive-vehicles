package io.github.brooswitminecraft.div;
/**
 * MINECRAFT-519: optional, bounded axle anti-roll coupling between a paired left/right wheel on
 * the SAME axle - a genuine RESTORING coupling (amplifying the roll-induced differential between
 * the pair, same direction a stiffer real anti-roll bar does), not an equalizing one.
 *
 * <p><b>Review correction #1 (comments 53750/53752) over an earlier version of this class.</b> An
 * earlier revision blended each side toward the pair's own raw average load - backwards (it
 * eliminates the restoring differential at full strength instead of strengthening it) and blind
 * to {@link WheelLoadDistribution}'s own legitimately-unequal static load shares (a COM-off-
 * center axle has a real, zero-roll 400N/600N split that blending toward the average would
 * disturb).
 *
 * <p><b>Review correction #2 (comment 53770) over the FIRST correction.</b> Comparing each side's
 * LOAD against its own calibrated static-equilibrium FORCE baseline ({@code mass*gravity*
 * loadShare}) is still wrong whenever the pair's two spring RATES differ (which they do whenever
 * their static shares differ - {@code suspensionSpringForShare} calibrates a stiffer spring for
 * the side closer to the COM). A genuinely common-mode heave (both sides compress by the exact
 * same extra amount, zero actual roll) then produces UNEQUAL force deviations from each side's
 * own baseline, because the same travel delta scales through two different rates - comment
 * 53770's own worked reproduction (x=+1.2/-0.8, shares .2/.3, a uniform +20% compression common
 * to both sides) manufactured a -40Nm "roll" torque out of pure heave.
 *
 * <p><b>The actual fix: couple DIFFERENTIAL TRAVEL (compression), not force or force-deviation.
 * </b> Every wheel's calibrated ride-height equilibrium sits at the SAME absolute compression
 * ({@code suspensionTravel*.5}, see {@code suspensionSpringForShare}'s own doc - share only
 * changes the RATE, never the target compression), so {@code compressionA - compressionB} is
 * already a common-heave/common-velocity-neutral roll signal with no baseline or rate-dividing
 * needed at all: identical for every uneven STATIC share, exactly zero for a true common-mode
 * heave (every wheel moves by the same real distance) regardless of how different the pair's own
 * calibrated rates are, and it says nothing whatsoever about velocity (a common sink rate with
 * matching compression is automatically neutral too, since velocity never enters the signal).
 * {@link #transfer} converts that differential travel into a force with the pair's own AVERAGE
 * spring rate ({@code (rateA+rateB)/2} - a physically reasonable, swap-symmetric choice; using
 * either side's rate alone would itself reintroduce an asymmetry), scaled by {@code stiffness},
 * then moves exactly that (bounded) amount from one side to the other.
 *
 * <p><b>Axle/pair identification is purely geometric</b> (ticket's own "identify actual axle/wheel
 * grouping from geometry/config without vehicle-ID hacks"): two wheels are the same axle's
 * left/right pair iff they share the same longitudinal anchor {@code z} (within floating-point
 * tolerance) AND sit on strictly opposite sides of the chassis centerline ({@code x} of opposite
 * sign - see {@link #pairPartners}). Any other z-group shape (0, 1, or 3+ wheels sharing a z, a
 * skid-steer/centered wheel at {@code x==0}, two wheels on the SAME side) is an unsupported layout
 * for THAT group and is left completely uncoupled - conservative by construction, never guessed.
 *
 * <p><b>Bounded and conserved.</b> {@code stiffness<=0} (the documented default/FALLBACK) returns
 * exactly zero transfer - exact zero/default equivalence, not merely close. The one computed
 * transfer is capped at the actual donor side's own current LOAD (never below zero) - never an
 * independent per-side clamp; the SAME single amount is added to one side and subtracted from the
 * other, so the pair's total is exactly conserved by construction, never two independently-
 * clamped ends that would have to manufacture or destroy net vertical force to stay consistent.
 *
 * <p>A wheel whose paired partner has no contact THIS tick (airborne, raycast miss, or simply
 * absent from the current hit list for any other reason) must be left completely untouched by the
 * caller - there is no real partner load to redistribute with, and inventing one would manufacture
 * force from nothing. That gating is the caller's responsibility (it alone knows which wheels
 * actually have a hit this tick); this class only ever computes the pairing and the transfer once
 * both sides are already known to be real.
 */
public final class AntiRoll {
 private AntiRoll() {}
 /** Geometric left/right pairing, purely from each wheel's own {@code (x,z)} anchor relative to
  * the chassis center/COM (same frame {@link WheelLoadDistribution} already uses) - see class doc.
  * Returns one partner index per wheel, or {@code -1} if that wheel has no supported pair.
  * {@code x} and {@code z} must be the same length; a length mismatch is a caller bug, not a
  * runtime-data case this validates. */
 public static int[] pairPartners(double[] x, double[] z) {
  int n = x.length;
  int[] partner = new int[n];
  java.util.Arrays.fill(partner, -1);
  double eps = 1e-6;
  boolean[] grouped = new boolean[n];
  for (int i = 0; i < n; i++) {
   if (grouped[i]) continue;
   java.util.List<Integer> group = new java.util.ArrayList<>();
   group.add(i);
   for (int j = i + 1; j < n; j++) {
    if (!grouped[j] && Math.abs(z[j] - z[i]) <= eps) group.add(j);
   }
   for (int idx : group) grouped[idx] = true;
   if (group.size() == 2) {
    int a = group.get(0), b = group.get(1);
    double sa = Math.signum(x[a]), sb = Math.signum(x[b]);
    // Strictly opposite, non-zero signs only - a centered wheel (x==0, e.g. a skid-steer rail
    // or a single-wheel axle) or two wheels on the SAME side is an unsupported pairing, left
    // uncoupled rather than guessed.
    if (sa != 0 && sb != 0 && sa != sb) { partner[a] = b; partner[b] = a; }
   }
  }
  return partner;
 }
 /**
  * The bounded, differential-travel-driven restoring transfer (see class doc) - the amount to ADD
  * to side A's own current load and SUBTRACT from side B's (a negative result means the flow
  * actually runs from A to B). {@code compressionA}/{@code compressionB} are each side's current
  * raw suspension compression THIS tick (same units/frame as {@code PhysicsBridge.WheelHit.
  * compression()} - common-heave/common-velocity-neutral by construction, see class doc);
  * {@code rateA}/{@code rateB} are each side's own calibrated spring rate (see {@code
  * PhysicsBridge.suspensionSpringForShare}), used only to convert the travel differential into a
  * force via the pair's own average rate; {@code normalA}/{@code normalB} are their current,
  * already-computed dynamic LOADS this tick, used only to bound the donor side. {@code
  * stiffness<=0}, or any non-finite input, returns exactly {@code 0.0} (no coupling) rather than
  * extrapolating past the documented bound or propagating a non-finite result.
  */
 public static double transfer(double compressionA, double compressionB, double rateA, double rateB,
     double normalA, double normalB, double stiffness) {
  if (!Double.isFinite(stiffness) || stiffness <= 0) return 0.0;
  if (!Double.isFinite(compressionA) || !Double.isFinite(compressionB)
    || !Double.isFinite(rateA) || !Double.isFinite(rateB)
    || !Double.isFinite(normalA) || !Double.isFinite(normalB)) return 0.0;
  // Zero for a true common-mode heave (every wheel at the same compression) or a common sink
  // velocity (never even read here) regardless of how different the pair's own calibrated rates
  // are - comment 53770's own "common-heave neutrality under genuinely unequal shares" fix.
  double travelDifferential = compressionA - compressionB;
  double averageRate = (rateA + rateB) / 2.0;
  double desired = stiffness * averageRate * travelDifferential / 2.0;
  if (!Double.isFinite(desired)) return 0.0;
  // Bound the ONE equal-and-opposite transfer against whichever side is actually donating this
  // tick, never an independent per-side clamp - see class doc.
  if (desired >= 0) return Math.min(desired, Math.max(0.0, normalB));
  return -Math.min(-desired, Math.max(0.0, normalA));
 }
}
