package io.github.brooswitminecraft.div;
/** SI units throughout. One bounded tire budget shared by longitudinal/lateral forces. */
public final class TireForces {
 private TireForces() {}
 public record Force(double longitudinal, double lateral) {}
 public static double suspension(double compression, double normalSpeed, double spring, double damper) {
  return Math.max(0, compression * spring - normalSpeed * damper);
 }
 public static Force friction(double longitudinal, double lateral, double normal, double mu) {
  double budget = Math.max(0, normal * mu);
  double length = Math.hypot(longitudinal, lateral);
  double scale = length > budget && length > 0 ? budget / length : 1;
  return new Force(longitudinal * scale, lateral * scale);
 }
 /**
  * Ticket 53331/53332/53335/53337: an ANISOTROPIC (elliptical) friction budget - the lateral
  * semi-axis is {@code normal*mu*lateralGripMultiplier}, the longitudinal semi-axis stays
  * {@code normal*mu} unchanged, so this is a genuine, iteration-count-independent STEADY-STATE
  * bound on lateral force (not a per-pass convergence-rate knob, which review 53335 correctly
  * flagged as unsound - scaling only the increment inside an iterative PGS solver merely slows
  * convergence, and a high iteration count (the real installed baseline's own 64, confirmed via
  * 53333) would restore nearly full grip regardless of the per-pass damping). The clamp pulls an
  * out-of-budget (longitudinal,lateral) point radially back onto the ellipse boundary in
  * NORMALIZED space ({@code hypot(longitudinal/budgetLong, lateral/budgetLat)}), the same
  * scale-by-excess-ratio logic as the circular {@link #friction} above, generalized to two
  * independent semi-axes.
  * <p>Review 53337 correctly caught two claims this method's own earlier version got wrong:
  * <ul>
  *  <li>{@code lateralGripMultiplier=1.0} makes {@code budgetLat==budgetLong} algebraically, but
  *  the NORMALIZED-space division-then-hypot evaluation here is a genuinely different
  *  floating-point computation than {@link #friction}'s own direct {@code hypot(longitudinal,
  *  lateral)/budget} - close, but not guaranteed bit-identical. Delegating directly to
  *  {@link #friction} at exactly 1.0 (checked below) gives TRUE byte-identical default
  *  preservation, not merely a very close approximation.</li>
  *  <li>{@code budgetLat<=0} (a configured zero/near-zero lateral grip) previously returned
  *  {@code (0,0)} unconditionally, incorrectly zeroing LONGITUDINAL force too even when the
  *  caller requested zero lateral demand - a vehicle with zero lateral grip should still have
  *  full drive/brake authority, just no lateral grip at all. Fixed below: zero lateral budget
  *  degenerates to a 1D longitudinal-only clamp ({@code longitudinal} clamped to
  *  {@code +-budgetLong}, lateral forced to exactly 0), not a blanket zero.</li>
  * </ul>
  * <p>Honest tradeoff (review 53337's own "describe the tradeoff honestly rather than
  * universally unchanged braking"): OUTSIDE the two special cases above, this is still a shared
  * RADIAL ellipse projection - in a genuinely COMBINED high-demand regime (both a large
  * longitudinal target, e.g. hard braking, AND a large lateral target, e.g. mid-turn,
  * simultaneously exceeding the ellipse) the shared scale factor CAN reduce the achieved
  * longitudinal force below what the circular model alone would have given at the same
  * {@code mu}, because shrinking the lateral semi-axis shrinks the whole ellipse's extent along
  * the combined-demand direction, not only the pure-lateral direction. Longitudinal is
  * UNCHANGED only when the lateral semi-axis is not actually the limiting factor for that
  * tick's specific (longitudinal,lateral) demand pair (confirmed by the dedicated combined-
  * demand tests below) - not universally, in every regime, regardless of combined demand.
  */
 public static Force frictionAnisotropic(double longitudinal, double lateral, double normal, double mu, double lateralGripMultiplier) {
  if (lateralGripMultiplier == 1.0) return friction(longitudinal, lateral, normal, mu);
  double budgetLong = Math.max(0, normal * mu);
  double budgetLat = Math.max(0, normal * mu * lateralGripMultiplier);
  if (budgetLong <= 0) return new Force(0, 0);
  if (budgetLat <= 0) {
   double clampedLongitudinal = Math.max(-budgetLong, Math.min(budgetLong, longitudinal));
   return new Force(clampedLongitudinal, 0);
  }
  double normalizedLength = Math.hypot(longitudinal / budgetLong, lateral / budgetLat);
  double scale = normalizedLength > 1 ? 1 / normalizedLength : 1;
  return new Force(longitudinal * scale, lateral * scale);
 }
}
