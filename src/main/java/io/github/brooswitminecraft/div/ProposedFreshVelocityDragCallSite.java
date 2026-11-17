package io.github.brooswitminecraft.div;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * UNWIRED production-integration DESIGN for review only (MINECRAFT-545/547, John's comments
 * 54700/54703/54706). NOT called from {@code BuoyantForces.apply} or any other production path.
 *
 * <p>CORRECTED ACCEPTANCE CRITERION (54706): a prior version of this design, and its own test,
 * treated "the combined buoyancy+drag impulse adds kinetic energy" as evidence of a problem.
 * That is WRONG - buoyancy is PHYSICALLY ALLOWED to add kinetic energy (it is the force that
 * accelerates a resting submerged body upward; requiring the total impulse to be non-energy-
 * adding would require capping BUOYANCY itself, which every instruction in this investigation
 * explicitly forbids). The actual goal is narrower: bounded DRAG must never add energy BEYOND
 * what buoyancy alone, for that same step, already would have - buoyancy itself stays entirely
 * free to add energy.
 *
 * <p><b>The virtual-buoyancy-first scheme (54706's own authorized design)</b>: compute a
 * VIRTUAL post-buoyancy velocity state (buoyancy's own impulse applied to a COPY of the current
 * velocity - never the real body), recompute drag's own physical force FRESH from that virtual
 * state (never the original, pre-buoyancy state), and bound it against that SAME virtual state.
 * Buoyancy's own force is NEVER touched, scaled, or capped.
 * <p>This still results in exactly ONE real {@code applyImpulseAtPoint} call per float, matching
 * today's production code exactly - because impulse application is a simple ADDITIVE velocity
 * update (linear: {@code v += impulse/mass}; angular: {@code omega += invInertia*(arm x impulse)}),
 * applying ONE combined impulse ({@code buoyancy + boundedDrag}) to the REAL body produces the
 * IDENTICAL resulting velocity as applying buoyancy, then drag, as two SEPARATE sequential
 * impulses (impulse summation and cross-product both distribute over addition - there is no
 * order dependence at the velocity-update level, only kinetic energy, a quadratic function of
 * velocity, depends on path in general - but since both orderings reach the SAME final velocity
 * here, the final KE is identical too, trivially). This is what lets drag be bounded against
 * the state it will ACTUALLY see without needing two real native calls per float.
 * <p>Numeric-precision note (54706): the NATIVE engine computes in single-precision
 * ({@code f32]} - Rapier's own {@code rapier3d} crate defaults to {@code features = ["dim3",
 * "f32", "std"]}, confirmed via its pinned {@code Cargo.toml}, and {@code sable_rapier}'s own
 * dependency on it does not opt into the {@code f64} variant) while this design computes in
 * Java {@code double} (f64). The CONFIRMED formula equivalence (VERIFICATION.md "Corrections
 * per 54703") is a formula match, NOT a claim of bit-identical numeric results - any validation
 * against a real native run must use a tolerance appropriate to {@code f32} precision, not an
 * exact-equality or double-precision-tight comparison.
 */
final class ProposedFreshVelocityDragCallSite {
 private ProposedFreshVelocityDragCallSite() {}

 /** Recomputes a drag force from a (possibly virtual) linear/angular velocity pair - the real
  * {@code BuoyantForces.apply} would supply its own existing per-axis directional-drag formula
  * here; kept as a callback so this design does not need to duplicate that formula. */
 @FunctionalInterface interface DragForceFromVelocity {
  Vector3d compute(Vector3dc linearVelocity, Vector3dc angularVelocity);
 }

 /**
  * The virtual-buoyancy-first combined force for one float, per the class doc's scheme.
  * {@code buoyancyForce} is returned unmodified, additively, in the result - never scaled.
  * {@code recomputeDrag} is called with the VIRTUAL post-buoyancy velocity, never the original.
  */
 static Vector3d virtualBuoyancyFirstCombinedForce(MassData massData, Vector3dc arm, Vector3dc buoyancyForce,
     DragForceFromVelocity recomputeDrag, Vector3dc localLinearVelocity, Vector3dc localAngularVelocity, double dt) {
  SequentialImpulseEnergyLedger virtualLedger = new SequentialImpulseEnergyLedger(massData, localLinearVelocity, localAngularVelocity);
  double buoyancyMagnitude = buoyancyForce.length();
  if (Double.isFinite(buoyancyMagnitude) && buoyancyMagnitude > 0 && Double.isFinite(dt) && dt > 0) {
   Vector3d buoyancyDirection = new Vector3d(buoyancyForce).normalize();
   virtualLedger.applyImpulseAndMeasureEnergyChange(arm, buoyancyDirection, buoyancyMagnitude * dt);
  }

  Vector3d recomputedDragForce = recomputeDrag.compute(virtualLedger.linearVelocity(), virtualLedger.angularVelocity());
  Vector3d boundedDrag = ProposedBoundedDragIntegration.boundedDragForce(
    massData, arm, recomputedDragForce, virtualLedger.linearVelocity(), virtualLedger.angularVelocity(), dt);

  return new Vector3d(buoyancyForce).add(boundedDrag);
 }
}
