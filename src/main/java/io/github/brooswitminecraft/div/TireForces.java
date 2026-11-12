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
  * Ticket 53331/53332/53335: an ANISOTROPIC (elliptical) friction budget - the lateral semi-axis
  * is {@code normal*mu*lateralGripMultiplier}, the longitudinal semi-axis stays
  * {@code normal*mu} unchanged, so this is a genuine, iteration-count-independent STEADY-STATE
  * bound on lateral force (not a per-pass convergence-rate knob, which review 53335 correctly
  * flagged as unsound - scaling only the increment inside an iterative PGS solver merely slows
  * convergence, and a high iteration count (the real installed baseline's own 64, confirmed via
  * 53333) would restore nearly full grip regardless of the per-pass damping). The clamp pulls an
  * out-of-budget (longitudinal,lateral) point radially back onto the ellipse boundary in
  * NORMALIZED space ({@code hypot(longitudinal/budgetLong, lateral/budgetLat)}), exactly the
  * same scale-by-excess-ratio logic as the circular {@link #friction} above, generalized to two
  * independent semi-axes.
  * <p>{@code lateralGripMultiplier=1.0} makes {@code budgetLat == budgetLong}, under which this
  * method is algebraically IDENTICAL to {@link #friction} (normalizedLength reduces exactly to
  * {@code hypot(longitudinal,lateral)/budget} - same formula, same result) - the default/no-op
  * case is therefore byte-for-byte equivalent to the existing circular model, not merely
  * approximately so. Longitudinal is NEVER touched by {@code lateralGripMultiplier} except
  * through the ellipse's own shared radial scale factor when the COMBINED point is out of
  * budget - the longitudinal semi-axis itself, and brake/drive's own prescribed targets upstream,
  * are completely unaffected, so accel/brake grip is preserved exactly as review required.
  */
 public static Force frictionAnisotropic(double longitudinal, double lateral, double normal, double mu, double lateralGripMultiplier) {
  double budgetLong = Math.max(0, normal * mu);
  double budgetLat = Math.max(0, normal * mu * lateralGripMultiplier);
  if (budgetLong <= 0 || budgetLat <= 0) return new Force(0, 0);
  double normalizedLength = Math.hypot(longitudinal / budgetLong, lateral / budgetLat);
  double scale = normalizedLength > 1 ? 1 / normalizedLength : 1;
  return new Force(longitudinal * scale, lateral * scale);
 }
}
