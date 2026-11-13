package io.github.brooswitminecraft.div;
/**
 * MINECRAFT-519: optional, bounded axle anti-roll coupling between a paired left/right wheel on
 * the SAME axle - a genuine RESTORING coupling (amplifying the roll-induced load difference
 * between the pair, same direction a stiffer real anti-roll bar does), not an equalizing one.
 *
 * <p><b>Review correction (comments 53750/53752) over an earlier version of this class.</b> An
 * earlier revision blended each side toward the pair's own raw average load. That is backwards:
 * a real anti-roll bar INCREASES the load difference between a tilted axle's two wheels (it
 * resists differential wheel travel, which - because each wheel's spring already pushes back
 * harder the more it is compressed - manifests as MORE restoring torque about the roll axis for
 * the same tilt, not less). Blending toward the average does the opposite: at full strength it
 * makes the two loads EXACTLY equal, which eliminates the restoring differential entirely rather
 * than strengthening it. It also ignored this ticket's own {@link WheelLoadDistribution}: two
 * wheels on the same axle legitimately carry UNEQUAL static loads whenever the chassis COM sits
 * off-center between them (comment 53752's own worked example - 400N/600N at rest, geometrically
 * correct, zero actual roll) - blending those toward a shared average manufactures a roll torque
 * that was never really there.
 *
 * <p><b>The fix: couple the DEVIATION from each wheel's own static baseline, not the raw load.</b>
 * Each wheel's own {@code baseline} is its calibrated static-equilibrium load (see {@code
 * PhysicsBridge.suspensionSpringForShare}'s own doc - {@code mass*gravity*loadShare}, the exact
 * load this wheel's spring is calibrated to support at rest). {@code normal-baseline} is then
 * purely the ROLL-induced (or any other transient) component - zero whenever this wheel sits
 * exactly at its own static equilibrium, regardless of how that baseline compares to its
 * partner's. {@link #transfer} amplifies the pair's SIGNED difference in that deviation by
 * {@code stiffness} and moves exactly that (bounded) amount from one side to the other - the pair
 * genuinely restoring MORE than an uncoupled pair would for the same tilt, while two wheels
 * sitting exactly at their own (possibly unequal) static baselines see zero transfer no matter
 * how different those baselines are from each other.
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
 * exactly zero transfer - exact zero/default equivalence, not merely close. A positive transfer
 * is capped at the donor side's own current load (never below zero); the SAME single transfer
 * amount is added to one side and subtracted from the other, so the pair's total is exactly
 * conserved by construction - never two independently-clamped ends that would have to
 * manufacture or destroy net vertical force to stay consistent.
 *
 * <p>A wheel whose paired partner has no contact THIS tick (airborne, raycast miss, or simply
 * absent from the current hit list for any other reason) must be left completely untouched by the
 * caller - there is no real partner load to redistribute with, and inventing one would manufacture
 * force from nothing. That gating is the caller's responsibility (it alone knows which wheels
 * actually have a hit this tick); this class only ever computes the pairing and the transfer once
 * both sides, and both their static baselines, are already known to be real.
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
  * The bounded, baseline-relative restoring transfer (see class doc) - the amount to ADD to side
  * A's own load and SUBTRACT from side B's (a negative result means the flow actually runs from
  * A to B). {@code baselineA}/{@code baselineB} are each side's own static-equilibrium load (see
  * {@code PhysicsBridge.suspensionSpringForShare}'s doc); {@code normalA}/{@code normalB} are
  * their current, already-computed dynamic loads this tick. {@code stiffness<=0}, or any
  * non-finite input, returns exactly {@code 0.0} (no coupling) rather than extrapolating past the
  * documented bound or propagating a non-finite result.
  */
 public static double transfer(double normalA, double baselineA, double normalB, double baselineB, double stiffness) {
  if (!Double.isFinite(stiffness) || stiffness <= 0) return 0.0;
  if (!Double.isFinite(normalA) || !Double.isFinite(normalB) || !Double.isFinite(baselineA) || !Double.isFinite(baselineB)) return 0.0;
  // The pair's SIGNED roll-induced difference - zero whenever both sides sit exactly at their
  // own (possibly unequal) static baseline, regardless of how different those baselines are
  // from each other (comment 53752's own "balanced static loads... zero roll torque" case).
  double deviationA = normalA - baselineA, deviationB = normalB - baselineB;
  double rollSignal = deviationA - deviationB;
  if (!Double.isFinite(rollSignal)) return 0.0;
  double desired = stiffness * rollSignal / 2.0;
  if (!Double.isFinite(desired)) return 0.0;
  // Bound the ONE equal-and-opposite transfer against whichever side is actually donating this
  // tick, never an independent per-side clamp - see class doc.
  if (desired >= 0) return Math.min(desired, Math.max(0.0, normalB));
  return -Math.min(-desired, Math.max(0.0, normalA));
 }
}
