package io.github.brooswitminecraft.div;
/**
 * MINECRAFT-532: bounded, opt-in redistribution of a wheel's own already-assigned drive-force
 * share, among the OTHER wheels on the SAME geometric axle, when that wheel has no ground contact
 * this tick. Operates downstream of {@link DriveBiasDistribution#shareRatios} - that class decides
 * HOW MUCH of the vehicle's whole drive budget each axle (front/rear) receives and splits it
 * evenly across that axle's wheels; this class decides, within one axle's own already-assigned
 * total, how to re-split it across just that axle's currently-GROUNDED wheels when one or more of
 * its wheels have no contact this tick.
 *
 * <p>This is NOT a limited-slip differential, a wheel-spin/traction-control system, or any
 * slip-sensing mechanism - it never reads wheel angular velocity, and the ticket is explicit that
 * kinematically-assigned wheel angular velocity must not be treated as measured slip. The only
 * signal this class consults is each wheel's own measured ground-contact state and its own
 * suspension-contact load ({@code WheelHit.normal()} - the real per-wheel vertical contact force
 * {@code PhysicsBridge}'s raycast/suspension solve already produces this tick, including after
 * MINECRAFT-519's anti-roll redistribution) - genuine contact/load evidence, not an assumption
 * about which wheels are "supposed to" be driven.
 *
 * <p><b>Default off is a byte-identical no-op.</b> {@code enabled=false} returns {@code
 * driveShareRatio} completely unchanged (a defensive copy, never the same array reference) -
 * every wheel keeps exactly the ratio {@link DriveBiasDistribution#shareRatios} already assigned
 * it, including an airborne wheel's now-unusable share, which (as today, pre-this-ticket) is
 * simply never applied at all ({@code PhysicsBridge.stepGround} never builds a {@code WheelHit}
 * for a {@code HitResult.Type.MISS} wheel - see that method's own "no phantom/airborne
 * contribution" doc) rather than redistributed anywhere.
 *
 * <p><b>Axle grouping is geometric</b> - the exact same max-{@code z}-within-{@link
 * #AXLE_EPSILON} front/rear classification {@link DriveBiasDistribution} and {@link
 * BrakeBiasDistribution} already use, over the FULL {@code z} array (every wheel, grounded or
 * not) - so this class's own front/rear groups always match the groups {@code driveShareRatio}
 * was actually assigned against.
 *
 * <p><b>No cross-axle theft.</b> The front and rear groups are redistributed completely
 * independently - a rear wheel's lost share can never flow to a front wheel, and vice versa,
 * regardless of how lopsided one axle's own contact/load state is this tick. Each axle's own
 * {@code driveShareRatio} total (summed over every wheel in that axle, grounded or not) is
 * exactly preserved across the redistribution - this moves budget WITHIN an axle, it never
 * changes how much any axle receives in total.
 *
 * <p><b>Weighting among multiple grounded axle-mates is by load, not an even split.</b> A
 * grounded wheel bearing more of the axle's real contact load receives proportionally more of
 * the axle's redistributed budget ({@code load[i]/sum(load over grounded wheels in this axle)})
 * - itself still a bounded geometric/contact-evidence redistribution of the SAME pre-existing
 * budget, never a new torque-biasing/differential model.
 *
 * <p><b>Bounded fallback: nothing to redistribute against.</b> Whenever an axle has no grounded
 * wheel with positive load at all (every wheel in that axle airborne, or every grounded wheel
 * reporting exactly zero contact load - a real but degenerate tick), that axle's ratios are left
 * completely untouched (identical to the disabled case for that axle) rather than fabricating a
 * split with nothing real to weight it by. An airborne wheel always receives ratio {@code 0}
 * whenever its axle DOES have somewhere real to send that budget - moot for force application
 * (it never reaches a {@code WheelHit} regardless), but keeps this method's own returned array an
 * honest description of where the budget actually went.
 */
public final class AxleDriveForceSharing {
 private AxleDriveForceSharing() {}
 /** Same physical axle-row tolerance as {@link DriveBiasDistribution#AXLE_EPSILON} - both
  * classify the same geometry and must never disagree about which wheels share an axle row. */
 static final double AXLE_EPSILON = 1e-6;
 /**
  * {@code z[i]}: wheel {@code i}'s longitudinal anchor (same frame/role as {@link
  * DriveBiasDistribution#shares}'s own {@code z}). {@code driveShareRatio[i]}: that wheel's
  * already-assigned ratio from {@link DriveBiasDistribution#shareRatios} - what gets redistributed,
  * never recomputed. {@code grounded[i]}: this wheel's real ground-contact state this tick (a
  * {@code false} entry's own {@code load[i]} is never consulted). {@code load[i]}: this wheel's
  * own real measured contact load ({@code WheelHit.normal()}) when grounded - ignored, and safe
  * to pass as any value including {@code 0}/{@code NaN}, when {@code grounded[i]} is {@code
  * false}. {@code enabled}: the resolved {@code Overrides.Tuning#axleDriveForceSharing} opt-in
  * flag - {@code false} (including the documented unset/{@code null} default) is the byte-
  * identical no-op described in this class's own doc.
  * <p>All four arrays must share {@code z}'s own length and per-index wheel identity - the same
  * convention every sibling per-wheel array in {@code PhysicsBridge.stepGround} already follows
  * (e.g. {@code loadShareZ}/{@code driveShareRatios}/{@code brakeShareRatios}).
  */
 public static double[] shareRatios(double[] z, double[] driveShareRatio, boolean[] grounded, double[] load, boolean enabled) {
  int n = z.length;
  double[] result = new double[n];
  System.arraycopy(driveShareRatio, 0, result, 0, n);
  if (!enabled || n == 0) return result;
  double maxZ = Double.NEGATIVE_INFINITY;
  for (double v : z) maxZ = Math.max(maxZ, v);
  boolean[] isFront = new boolean[n];
  for (int i = 0; i < n; i++) isFront[i] = z[i] >= maxZ - AXLE_EPSILON;
  redistributeGroup(isFront, true, driveShareRatio, grounded, load, result);
  redistributeGroup(isFront, false, driveShareRatio, grounded, load, result);
  return result;
 }
 /** Redistributes exactly one axle group ({@code front}=true is the max-{@code z} group, {@code
  * front}=false is every other wheel - see this class's own "lumps every non-front row into one
  * rear group" convention, inherited unchanged from {@link DriveBiasDistribution}). Mutates only
  * {@code result} indices belonging to this group; every other index is left to the caller's
  * other call for the opposite group. */
 private static void redistributeGroup(boolean[] isFront, boolean front, double[] driveShareRatio, boolean[] grounded, double[] load, double[] result) {
  int n = isFront.length;
  double totalAssigned = 0, groundedLoadSum = 0;
  for (int i = 0; i < n; i++) {
   if (isFront[i] != front) continue;
   totalAssigned += driveShareRatio[i];
   if (grounded[i]) {
    double l = load[i];
    if (Double.isFinite(l) && l > 0) groundedLoadSum += l;
   }
  }
  if (groundedLoadSum <= 0) return; // nothing real to weight a split by - leave this axle's assigned ratios untouched
  for (int i = 0; i < n; i++) {
   if (isFront[i] != front) continue;
   if (!grounded[i]) { result[i] = 0; continue; } // airborne - budget moved to this axle's grounded wheels; never actually applied either way
   double l = load[i];
   result[i] = (Double.isFinite(l) && l > 0) ? totalAssigned * (l / groundedLoadSum) : 0;
  }
 }
}
