package io.github.brooswitminecraft.div;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * UNWIRED production-integration DESIGN for review only (MINECRAFT-545/547, John's comment
 * 54703's own follow-up request for "a reviewable actual call-site integration diff"). NOT
 * called from {@code BuoyantForces.apply} or any other production path.
 *
 * <p>Mirrors {@code BuoyantForces.apply}'s own REAL per-float shape as closely as possible,
 * without a live {@code RigidBodyHandle}/{@code ServerLevel} (so it stays pure and testable):
 * same frame convention (LOCAL frame throughout - {@code localLinearVelocity}/
 * {@code localAngularVelocity} are expected ALREADY inverse-transformed, exactly as a real
 * caller would need to do to a fresh world-frame handle read, same as {@code BuoyantForces.apply}
 * already does for {@code arm}/{@code force} before calling {@code applyImpulseAtPoint}), same
 * separation of buoyancy and drag into two force vectors before they are summed into one
 * combined impulse.
 *
 * <p><b>Fresh velocity timing</b>: this method's own {@code localLinearVelocity}/
 * {@code localAngularVelocity} parameters are meant to represent a FRESH, per-float read (e.g.
 * {@code inverse.transform(handle.getLinearVelocity(new Vector3d()))}) - not the real
 * {@code BuoyantForces.apply}'s own once-per-tick cached parameters (confirmed stale, see
 * VERIFICATION.md "Corrections per 54700"). This design does not itself perform that read (it
 * has no handle to read from) - a real integration would need to call it once per float, with a
 * fresh read each time, exactly where the real per-float loop currently uses its own stale
 * {@code velocity}/{@code angular} parameters instead.
 *
 * <p><b>Isolated drag vs. buoyancy ordering, and the OPEN cross-term question (54703)</b>: this
 * method bounds ONLY the drag force (via {@link ProposedBoundedDragIntegration#boundedDragForce})
 * and NEVER touches, scales, or caps buoyancy - buoyancy is added back, unmodified, after
 * bounding drag, exactly mirroring {@code BuoyantForces.apply}'s own existing
 * {@code buoyancyOnlyForce} separation. <b>This does NOT, by itself, prove the resulting COMBINED
 * (buoyancy+bounded-drag) impulse - the one single impulse actually applied in one
 * {@code applyImpulseAtPoint} call, exactly as the real code does today - stays non-energy-
 * adding.</b> {@code impulseEnergyChange} is linear in its own impulse magnitude plus a quadratic
 * self-term; summing two DIFFERENT additive force contributions (buoyancy, drag) into one
 * impulse before measuring its energy effect is subject to the SAME kind of cross-term the
 * confirmed 54694 finding already identified for summing two SEPARATE sequential impulses - just
 * in a one-shot form here (one impulse built from two additive vector contributions, rather than
 * two sequential impulse calls). Bounding drag's own magnitude in isolation says nothing about
 * the cross term between drag and buoyancy's OWN simultaneous contribution to that same combined
 * impulse. This is reported here as an OPEN, UNRESOLVED question - NOT solved by this class, and
 * not claimed to be. Solving it correctly would require evaluating the full combined impulse's
 * energy effect directly (e.g. via {@link Buoyancy#impulseEnergyChange} called ONCE with the
 * combined impulse, the way 54694 established is correct for multiple contributions) rather than
 * bounding drag in isolation - a materially different design, out of scope for this cycle.
 */
final class ProposedFreshVelocityDragCallSite {
 private ProposedFreshVelocityDragCallSite() {}

 /** One float's combined (buoyancy + bounded-drag) force, in LOCAL frame, mirroring
  * {@code BuoyantForces.apply}'s own per-float combination - EXCEPT drag is bounded against
  * fresh per-float state first (see class doc for what that bound does and does not prove about
  * the resulting combined impulse). */
 static Vector3d combinedFloatForce(MassData massData, Vector3dc arm, Vector3dc buoyancyOnlyForce,
     Vector3dc physicalDragForce, Vector3dc localLinearVelocity, Vector3dc localAngularVelocity, double dt) {
  Vector3d boundedDrag = ProposedBoundedDragIntegration.boundedDragForce(
    massData, arm, physicalDragForce, localLinearVelocity, localAngularVelocity, dt);
  return new Vector3d(buoyancyOnlyForce).add(boundedDrag);
 }
}
