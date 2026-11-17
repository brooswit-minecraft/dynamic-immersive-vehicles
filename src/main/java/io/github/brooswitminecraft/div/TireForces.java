package io.github.brooswitminecraft.div;
/** SI units throughout. One bounded tire budget shared by longitudinal/lateral forces. */
public final class TireForces {
 private TireForces() {}
 public record Force(double longitudinal, double lateral) {}
 public static double suspension(double compression, double normalSpeed, double spring, double damper) {
  return suspension(compression, normalSpeed, spring, damper, damper);
 }
 /**
  * MINECRAFT-551: {@link #suspension(double,double,double,double)} generalized to independently
  * configurable compression/rebound damper coefficients, selected by the SIGN of {@code
  * normalSpeed} - the same signed suspension-anchor velocity the 4-arg overload above already
  * takes, along {@code up} (see the caller in {@code PhysicsBridge.stepGround}: the anchor moving
  * toward the ground, {@code normalSpeed<0}, is the suspension COMPRESSING further, so {@code
  * damperCompression} applies; the anchor moving away from the ground, {@code normalSpeed>0}, is
  * the suspension REBOUNDING/extending, so {@code damperRebound} applies). At {@code
  * normalSpeed==0} the damping term is exactly zero regardless of which coefficient would be
  * picked, so the degenerate case is well-defined without a third branch. Direction is read from
  * the actual signed velocity, never from {@code compression} alone - a vehicle can be compressed
  * (positive {@code compression}) while its anchor is still moving away from the ground (a
  * reboundING wheel that has not yet reached zero compression), and that tick must still use
  * {@code damperRebound}.
  * <p>At {@code damperCompression==damperRebound} this is byte-identical to the 4-arg overload
  * (which now delegates here) - the exact "equal compression/rebound reproduces the existing
  * symmetric setting" equivalence this ticket requires. Still strictly dissipative and capped at
  * the caller's own {@code Math.max(0, ...)} / {@code Math.min(mass*gravity, ...)} - this method
  * supplies no tensile force and no energy injection on its own, same as the 4-arg overload.
  */
 public static double suspension(double compression, double normalSpeed, double spring, double damperCompression, double damperRebound) {
  double damper = normalSpeed < 0 ? damperCompression : damperRebound;
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
