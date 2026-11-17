package io.github.brooswitminecraft.div;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * UNWIRED production-integration DESIGN for review only (MINECRAFT-545/547, John's comment
 * 54700's own request for "a concrete production-integration design/diff for review"). NOT
 * called from {@code BuoyantForces.apply} or any other production path. Production activation
 * and native execution both remain gated, per every standing instruction in this investigation -
 * this class exists to be read and reviewed, not to run.
 *
 * <p>Demonstrates how {@link SequentialImpulseEnergyLedger} COULD bound each float's DRAG
 * impulse (never buoyancy - see below) against the body's live state, addressing the three
 * concerns 54700 raised about any real integration:
 * <ul>
 *   <li><b>Fresh velocity timing.</b> 54700 asked this be checked separately from native
 *   application synchronicity - and reading {@code BuoyantForces.apply} directly confirmed its
 *   per-float loop computes every float's {@code localVelocity} from the CALLER-SUPPLIED
 *   {@code velocity}/{@code angular} PARAMETERS (one snapshot taken once, before the loop runs),
 *   never a fresh per-float handle read - see VERIFICATION.md "Corrections per 54700". A real
 *   integration bounding against live state needs FRESH velocity for each float, so this design
 *   takes {@code currentLinearVelocity}/{@code currentAngularVelocity} as per-call inputs - a
 *   real caller would need to read {@code handle.getLinearVelocity()}/
 *   {@code handle.getAngularVelocity()} immediately before each float's own call, not reuse the
 *   method's own cached parameters as {@code BuoyantForces.apply} does today.</li>
 *   <li><b>Drag direction never reversed, buoyancy never touched.</b> This method only ever
 *   SCALES a drag force's magnitude toward zero; it never flips its sign, never boosts it past
 *   what the physical model already computed, and takes ONLY the drag contribution as input -
 *   buoyancy (already kept as a separate {@code buoyancyOnlyForce} value inside
 *   {@code BuoyantForces.apply} itself) is never passed in, touched, or considered part of any
 *   "budget" here.</li>
 *   <li><b>Sign of the bound, not just its magnitude, matters.</b> A forward (same-direction-as-
 *   the-drag-force) impulse is only energy-dissipative when it OPPOSES the point-velocity
 *   component along that direction ({@code s<0}); when the drag direction happens to AID that
 *   component instead ({@code s>=0} - possible because full point velocity includes a rotational
 *   contribution the axis-local drag force does not itself account for), ANY forward magnitude
 *   adds energy, so the bound in that case is 0, not {@link Buoyancy#maxDissipativeImpulseMagnitude}'s
 *   own "always opposing" convention applied blindly.</li>
 * </ul>
 * <p>Numerical tradeoff: scaling the drag magnitude toward zero (rather than, say, clamping
 * exactly at the bound) preserves direction exactly and never lands exactly at the energy-
 * neutral boundary (which, per {@code maxDissipativeImpulseMagnitude}'s own doc, causes a
 * velocity REVERSAL, not a "safe stop") - trading exact energy-neutrality for a strictly
 * interior, monotonically-safer result.
 * <p>Frame handling: this method performs NO frame transform of its own. {@code arm} and
 * {@code physicalDragForce} are expected in the SAME frame {@code BuoyantForces.apply} already
 * uses for its own {@code handle.applyImpulseAtPoint} calls (LOCAL/body frame - that method
 * already calls {@code inverse.transform(...)} on both {@code arm} and {@code force} before
 * passing them to the handle) - a real caller must supply already-local-frame vectors, exactly
 * as that existing call site already does; this design changes nothing about that convention.
 * <p>See {@link SequentialImpulseEnergyLedger}'s own class doc for the still-open caveat that
 * the solid-box inertia model used by this ticket's tests is not confirmed to match what
 * Rapier's own {@code createBox} actually computes internally - this design's bound is only as
 * good as that unconfirmed equivalence.
 */
final class ProposedBoundedDragIntegration {
 private ProposedBoundedDragIntegration() {}

 /**
  * Returns a drag force vector with the SAME direction as {@code physicalDragForce}, scaled
  * down (never up, never sign-flipped, possibly all the way to zero) so that the resulting
  * per-tick impulse ({@code |physicalDragForce| * dt}) stays within the sequential dissipative
  * bound at {@code arm} along {@code physicalDragForce}'s own direction, evaluated against the
  * given CURRENT linear/angular velocity - the caller's responsibility to supply fresh, per
  * float, per the class doc above. Returns {@code physicalDragForce} unchanged if it is already
  * within bound, or if it is zero.
  */
 static Vector3d boundedDragForce(MassData massData, Vector3dc arm, Vector3dc physicalDragForce,
     Vector3dc currentLinearVelocity, Vector3dc currentAngularVelocity, double dt) {
  double forceMagnitude = physicalDragForce.length();
  if (!(forceMagnitude > 0)) return new Vector3d(physicalDragForce);
  Vector3d direction = new Vector3d(physicalDragForce).normalize();

  Vector3d position = new Vector3d(arm).add(massData.getCenterOfMass());
  Vector3d pointVelocity = new Vector3d(currentAngularVelocity).cross(arm, new Vector3d()).add(new Vector3d(currentLinearVelocity));
  double s = pointVelocity.dot(direction);
  double k = massData.getInverseNormalMass(position, direction);

  // A FORWARD (same-direction-as-physicalDragForce) impulse is dissipative only when it OPPOSES
  // this point-velocity component (s<0); when it AIDS it (s>=0), any forward magnitude adds
  // energy, so the forward bound collapses to 0 here - using
  // Buoyancy.maxDissipativeImpulseMagnitude's own "always opposing" convention blindly in that
  // case would give the WRONG (nonzero) bound for this specific sign.
  double forwardImpulseBound = s < 0 ? Buoyancy.maxDissipativeImpulseMagnitude(s, k) : 0.0;

  double physicalImpulseMagnitude = forceMagnitude * dt;
  if (physicalImpulseMagnitude <= forwardImpulseBound) return new Vector3d(physicalDragForce);

  double scale = forwardImpulseBound / physicalImpulseMagnitude; // in [0,1) - never negative, never >1
  return new Vector3d(physicalDragForce).mul(scale);
 }
}
