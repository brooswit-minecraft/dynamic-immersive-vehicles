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
 * impulse (never buoyancy - see below) against the body's live state, addressing the concerns
 * 54700/54703 raised about any real integration:
 * <ul>
 *   <li><b>Fresh velocity timing.</b> Reading {@code BuoyantForces.apply} directly confirmed its
 *   per-float loop computes every float's {@code localVelocity} from the CALLER-SUPPLIED
 *   {@code velocity}/{@code angular} PARAMETERS (one snapshot taken once, before the loop runs),
 *   never a fresh per-float handle read - see VERIFICATION.md "Corrections per 54700". This
 *   design takes {@code currentLinearVelocity}/{@code currentAngularVelocity} as per-call inputs
 *   for exactly that reason - a real caller needs to supply them fresh, per float.</li>
 *   <li><b>Frame.</b> CORRECTED (54703): "read it fresh" is NOT the same thing as "it is already
 *   in the right frame." {@code arm}, {@code physicalDragForce}, {@code currentLinearVelocity},
 *   {@code currentAngularVelocity}, and {@code massData}'s own tensor must ALL already be
 *   expressed in the SAME single frame before calling this method - this method performs NO
 *   transform of its own. {@code BuoyantForces.apply} already operates in LOCAL/body frame for
 *   its own {@code handle.applyImpulseAtPoint} calls (it calls {@code inverse.transform(...)} on
 *   both {@code arm} and {@code force} first) - a real caller must apply that SAME
 *   {@code inverse.transform(...)} to a freshly-read WORLD-frame velocity before passing it
 *   here; a fresh read alone does not perform that transform.</li>
 *   <li><b>Drag direction never reversed, buoyancy never touched.</b> This method only ever
 *   SCALES a drag force's magnitude toward zero; it never flips its sign, never boosts it past
 *   what the physical model already computed, and takes ONLY the drag contribution as input -
 *   buoyancy (already kept as a separate {@code buoyancyOnlyForce} value inside
 *   {@code BuoyantForces.apply} itself) is never passed in, touched, or considered part of any
 *   "budget" here. See {@code BuoyantForces.apply}'s own existing ordering for how drag and
 *   buoyancy are summed into one combined per-float impulse BEFORE being applied - this method
 *   does not itself resolve the cross-term question of whether bounding drag alone, before it is
 *   combined with a simultaneous buoyancy impulse, is sufficient; see VERIFICATION.md's own
 *   "Corrections per 54703" entry for that open point.</li>
 *   <li><b>Sign of the bound, not just its magnitude, matters.</b> A forward (same-direction-as-
 *   the-drag-force) impulse is only energy-dissipative when it OPPOSES the point-velocity
 *   component along that direction ({@code s<0}); when the drag direction happens to AID that
 *   component instead ({@code s>=0} - possible because full point velocity includes a rotational
 *   contribution the axis-local drag force does not itself account for), ANY forward magnitude
 *   adds energy, so the bound in that case is 0.</li>
 * </ul>
 * <p>CORRECTED numerical model (54703): a prior version of this class capped at
 * {@link Buoyancy#maxDissipativeImpulseMagnitude} (the ENERGY-NEUTRAL bound, {@code 2*|s|/k}),
 * documented as giving "a strictly interior, never-boundary result" - but its own scale factor
 * (bound divided by the physical impulse magnitude) makes the RESULTING impulse land EXACTLY AT
 * that bound whenever clamping triggers, which per that method's own doc causes a velocity
 * REVERSAL (same magnitude, flipped sign) - the exact opposite of "strictly interior." This was
 * a real documentation/behavior mismatch, confirmed by the prior version's own test asserting
 * that exact-boundary equality. Fixed: this version caps at
 * {@link Buoyancy#maxNonReversingImpulseMagnitude} instead - the point where the point-velocity
 * component is driven to EXACTLY ZERO, never past it - a genuinely physically-motivated choice
 * (drag arrests motion, it does not reverse it), not an arbitrary safety factor.
 * <p>Invalid-input contract (54703): a non-finite/non-positive {@code dt}, or a
 * {@code physicalDragForce} whose magnitude is non-finite, has no well-defined impulse budget -
 * both are guarded, returning a conservative ZERO force (not the raw force unchanged, and not a
 * NaN/Infinite result).
 * <p>See {@link SequentialImpulseEnergyLedger}'s own class doc for the native mass/inertia
 * equivalence this design's bound now depends on - CONFIRMED (not merely assumed) via
 * VERIFICATION.md's "Corrections per 54703" entry, which traces Rapier's actual pinned
 * {@code ColliderBuilder::mass(mass)} path through to the exact same {@code mass/3 * (...)}
 * formula {@link BoxInertia#solidBoxTensor} already implements.
 */
final class ProposedBoundedDragIntegration {
 private ProposedBoundedDragIntegration() {}

 /**
  * Returns a drag force vector with the SAME direction as {@code physicalDragForce}, scaled
  * down (never up, never sign-flipped, possibly all the way to zero) so that the resulting
  * per-tick impulse ({@code |physicalDragForce| * dt}) never drives the point-velocity component
  * along {@code physicalDragForce}'s own direction, at {@code arm}, PAST zero - see the class
  * doc's "CORRECTED numerical model" above. Evaluated against the given CURRENT linear/angular
  * velocity - the caller's responsibility to supply fresh and already in the same frame as
  * {@code arm}/{@code physicalDragForce}, per the class doc. Returns {@code physicalDragForce}
  * unchanged if it is already within bound, or if it is zero. Returns a ZERO vector if
  * {@code dt} is not finite and positive, or if {@code physicalDragForce}'s own magnitude is not
  * finite - there is no well-defined budget in either case.
  */
 static Vector3d boundedDragForce(MassData massData, Vector3dc arm, Vector3dc physicalDragForce,
     Vector3dc currentLinearVelocity, Vector3dc currentAngularVelocity, double dt) {
  double forceMagnitude = physicalDragForce.length();
  if (!Double.isFinite(forceMagnitude) || !Double.isFinite(dt) || !(dt > 0)) return new Vector3d(0, 0, 0);
  if (!(forceMagnitude > 0)) return new Vector3d(physicalDragForce);
  Vector3d direction = new Vector3d(physicalDragForce).normalize();

  Vector3d position = new Vector3d(arm).add(massData.getCenterOfMass());
  Vector3d pointVelocity = new Vector3d(currentAngularVelocity).cross(arm, new Vector3d()).add(new Vector3d(currentLinearVelocity));
  double s = pointVelocity.dot(direction);
  double k = massData.getInverseNormalMass(position, direction);

  // A FORWARD (same-direction-as-physicalDragForce) impulse only arrests (rather than reverses
  // or merely fails to add energy to) this point-velocity component when it OPPOSES it (s<0);
  // when it AIDS it (s>=0), any forward magnitude makes things worse, so the bound is 0.
  double forwardBound = s < 0 ? Buoyancy.maxNonReversingImpulseMagnitude(s, k) : 0.0;

  double physicalImpulseMagnitude = forceMagnitude * dt;
  if (physicalImpulseMagnitude <= forwardBound) return new Vector3d(physicalDragForce);

  double scale = forwardBound / physicalImpulseMagnitude; // in [0,1) - never negative, never >1
  return new Vector3d(physicalDragForce).mul(scale);
 }
}
