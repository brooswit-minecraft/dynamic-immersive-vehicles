package io.github.brooswitminecraft.div;
/**
 * MINECRAFT-519: optional, bounded axle anti-roll coupling between a paired left/right wheel on
 * the SAME axle, partially equalizing their already-computed suspension normal forces (see
 * {@code PhysicsBridge.stepGround}'s own per-wheel {@code normal}, AFTER {@link
 * WheelLoadDistribution}'s static load-share calibration and the per-wheel spring/damper/
 * compression solve - this never touches the per-wheel calibration itself, only redistributes
 * between two wheels that already have an independently-computed real load this tick).
 *
 * <p><b>Axle/pair identification is purely geometric</b> (ticket's own "identify actual axle/wheel
 * grouping from geometry/config without vehicle-ID hacks"): two wheels are the same axle's
 * left/right pair iff they share the same longitudinal anchor {@code z} (within floating-point
 * tolerance) AND sit on strictly opposite sides of the chassis centerline ({@code x} of opposite
 * sign - see {@link #pairPartners}). Any other z-group shape (0, 1, or 3+ wheels sharing a z, a
 * skid-steer/centered wheel at {@code x==0}, two wheels on the SAME side) is an unsupported layout
 * for THAT group and is left completely uncoupled - conservative by construction, never guessed.
 *
 * <p><b>The coupling itself is a dimensionless 0-1 blend toward the pair's own average load</b>
 * (ticket's "bounded... never independently clamp two ends and manufacture net vertical force"):
 * {@code stiffness==0} (the documented default/FALLBACK) changes NOTHING - exact zero/default
 * equivalence, not merely close. {@code stiffness==1} fully equalizes the pair (both end up at
 * their shared average - the "fully rigid bar" limit). Anything in between linearly interpolates.
 * Because each side's result is always a convex combination of {@code normalA} and {@code
 * normalB} (both already non-negative, real per-wheel loads), it can never go negative and can
 * never exceed the pair's own existing max - no separate clamp is needed, or possible to forget,
 * for either side, and the pair's sum is exactly conserved by construction (see {@link
 * #blendedNormalA}'s own algebra) rather than two independently-clamped ends that would have to
 * manufacture or destroy net vertical force to stay consistent.
 *
 * <p><b>This couples LOAD, not travel.</b> A real torsion-bar anti-roll bar is usually described
 * as resisting differential wheel TRAVEL; this coupling instead equalizes the pair's already-
 * computed LOAD directly, each tick - the ticket's own stated contract is "conserved suspension
 * loads", not "equalized travel". The two are closely related but not identical: at {@code
 * stiffness==1.0}, both sides' loads become EXACTLY equal starting the very first tick they are
 * coupled, which - for a pair sharing the same calibrated spring/damper/effective-mass (the
 * common case) - also makes their acceleration (and so their velocity) identical from then on.
 * The pair's two compressions then evolve as their own shared AVERAGE (which still converges to
 * the ordinary ride-height equilibrium, exactly as an uncoupled wheel would) plus/minus a FIXED
 * offset equal to half the gap that existed the first coupled tick - every bit of load imbalance
 * is still eliminated every single tick, exactly as documented, but the pre-existing travel GAP
 * itself is frozen, never progressively closed, by a fully-rigid setting. A partial ({@code
 * 0<stiffness<1}) setting does not freeze anything - see {@code
 * PhysicsBridgeAntiRollSimulationTest} for the simulated settling behavior at both ends of the
 * range.
 *
 * <p>A wheel whose paired partner has no contact THIS tick (airborne, raycast miss, or simply
 * absent from the current hit list for any other reason) must be left completely untouched by the
 * caller - there is no real partner load to redistribute with, and inventing one would manufacture
 * force from nothing. That gating is the caller's responsibility (it alone knows which wheels
 * actually have a hit this tick); this class only ever computes the pairing and the blend once
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
 /** The bounded 0-1 blend toward the pair's own average, applied to side A (call again with the
  * arguments swapped for side B's own result - see class doc for why that is always the exact
  * conservation-preserving counterpart, never a separate clamp). {@code stiffness} outside
  * {@code [0,1]}, or any non-finite input, is treated as exactly 0 (no coupling) rather than
  * extrapolating past the documented bounds or propagating a non-finite result. */
 public static double blendedNormalA(double normalA, double normalB, double stiffness) {
  if (!Double.isFinite(stiffness) || stiffness <= 0) return normalA;
  if (!Double.isFinite(normalA) || !Double.isFinite(normalB)) return normalA;
  double s = Math.min(stiffness, 1.0);
  double average = (normalA + normalB) * 0.5;
  return normalA + (average - normalA) * s;
 }
}
