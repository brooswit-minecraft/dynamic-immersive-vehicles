package io.github.brooswitminecraft.div;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * UNWIRED diagnostic/proposal utility (MINECRAFT-545/547, John's comments 54697/54700). Not
 * called from {@code BuoyantForces.apply} or any other production path - no behavior change.
 *
 * <p>Resolves the previously-UNVERIFIED "native synchronicity" question (method-naming
 * inference only, per earlier comments in this investigation) by tracing the actual pinned
 * native source directly: {@code sable_rapier}'s {@code lib.rs}, at the same pinned tag/commit
 * used in 54413/54418 ({@code mc1.21.1-2.0.5-neoforge}, commit {@code 6966d292...}),
 * {@code Java_..._Rapier3D_applyForce}/{@code applyForceAndTorque} call Rapier's own
 * {@code RigidBody::apply_impulse}/{@code apply_torque_impulse} directly, unconditionally, on
 * every single native call - no accumulation buffer, no deferral to the next {@code step()}.
 * Combined with the already-decompiled Java call chain
 * ({@code RigidBodyHandle.applyImpulseAtPoint -> RapierPhysicsPipeline.applyImpulse ->
 * Rapier3D.applyForce}, one native call per Java call, confirmed via {@code javap}), this means:
 * every call {@code BuoyantForces.apply}'s own per-float loop makes to
 * {@code handle.applyImpulseAtPoint} mutates the body's REAL rigid-body velocity IMMEDIATELY -
 * so each float's impulse is applied against whatever velocity every PRIOR float's impulse (in
 * the same tick) already produced, never against one shared, frozen pre-tick snapshot.
 *
 * <p>This rules out the two prior multi-float models this investigation actually tried and
 * disproved: summing {@code impulseEnergyChange} per impulse against one shared snapshot
 * (confirmed wrong by John's 54694 counter-example) and calling it once with every float's
 * impulse combined into a single scalar {@code J} against a single {@code k} (wrong too, per
 * 54697 - different floats act at different points/directions, each with its own effective
 * mass; there is no one {@code k} that represents them jointly). This class implements a THIRD
 * model - SEQUENTIAL: apply each float's impulse as a real linear+angular velocity update, in
 * dispatch order, bounding each one against the CURRENT (already-updated) state at the moment it
 * is applied - never a stale snapshot (the exact gap John's comment 54691 originally named), and
 * confirmed consistent with how the native engine actually applies impulses.
 * <p>CORRECTED SCOPE (54700): immediate native application rules out the two models above, but
 * does NOT by itself rule out every possible correctly-formed VECTOR combined-impulse analysis
 * (e.g. accumulating a total linear impulse P and total angular impulse L across all floats and
 * evaluating one full vector energy expression against the pre-tick state) - only the two
 * specific scalar approaches already tried were shown wrong. This class is A valid model,
 * confirmed consistent with native semantics - it is not claimed to be the ONLY one.
 * <p>CORRECTED SCOPE (54700, separately): immediate native APPLICATION synchronicity says
 * nothing about whether {@code BuoyantForces.apply}'s own per-float FORCE CALCULATION actually
 * reads fresh velocity for each float - that is a distinct question, answered separately (see
 * VERIFICATION.md "Corrections per 54700") by reading {@code BuoyantForces.apply} itself: its
 * per-float loop computes every float's {@code localVelocity} from the CALLER-SUPPLIED
 * {@code velocity}/{@code angular} parameters (one snapshot, taken once before the loop runs),
 * not from a fresh per-float handle read - confirmed, not assumed.
 * <p>CAVEAT, NOT YET CLOSED (54700): the solid-box mass/inertia model this class's own tests use
 * ({@link TestMassData#solidBox}, matching {@link BoxInertia#solidBoxTensor}) is a CHOSEN model.
 * It is NOT confirmed to equal what Rapier itself actually computes internally for a cuboid
 * collider at {@code createBox} time (54413 already established Rapier computes its own mass
 * properties independently of Sable's Java-side {@code MassData}/{@code getMassTracker()} for
 * that creation path) - this class's energy predictions are only as good as that unconfirmed
 * equivalence, which remains open.
 */
final class SequentialImpulseEnergyLedger {
 private final MassData massData;
 private final Vector3d linearVelocity;
 private final Vector3d angularVelocity;

 SequentialImpulseEnergyLedger(MassData massData, Vector3dc initialLinearVelocity, Vector3dc initialAngularVelocity) {
  this.massData = massData;
  this.linearVelocity = new Vector3d(initialLinearVelocity);
  this.angularVelocity = new Vector3d(initialAngularVelocity);
 }

 Vector3dc linearVelocity() { return linearVelocity; }
 Vector3dc angularVelocity() { return angularVelocity; }

 /**
  * CONTRACT (clarified, 54700): {@code arm} is the application point's position RELATIVE TO
  * THE BODY'S OWN CENTER OF MASS - the standard convention {@code torque = arm x force} already
  * assumes, and what {@code BuoyantForces.apply}'s own existing {@code arm} values already are.
  * {@code MassData.getInverseNormalMass(position, direction)} takes a POSITION and subtracts
  * {@code getCenterOfMass()} internally - calling it with {@code arm} directly would be WRONG
  * whenever {@code getCenterOfMass()} is non-zero (a prior version of this class did exactly
  * that, latent only because every test's own {@code MassData} happened to return a zero COM).
  * Fixed here by adding the COM back before the call, so {@code position - COM} reduces back to
  * the given {@code arm} regardless of where the COM actually is.
  * <p>{@code direction} need NOT be pre-normalized by the caller - normalized once, internally,
  * consistently for both the velocity-component ({@code s}) and effective-mass ({@code k}) side
  * of the computation (a prior version used the raw, possibly non-unit {@code direction} for
  * {@code s} while {@code MassData.getInverseNormalMass} silently normalizes its own copy for
  * {@code k} - an inconsistency that would silently corrupt the result for any non-unit input).
  * A zero-length or non-finite {@code direction} throws {@link IllegalArgumentException} - there
  * is no meaningful direction to normalize.
  */
 double currentMaxDissipativeImpulseMagnitude(Vector3dc arm, Vector3dc direction) {
  Vector3d unitDirection = normalizedOrThrow(direction);
  Vector3d position = new Vector3d(arm).add(massData.getCenterOfMass());
  Vector3d pointVelocity = new Vector3d(angularVelocity).cross(arm, new Vector3d()).add(linearVelocity);
  double s = pointVelocity.dot(unitDirection);
  double k = massData.getInverseNormalMass(position, unitDirection);
  return Buoyancy.maxDissipativeImpulseMagnitude(s, k);
 }

 /**
  * Applies one impulse of the given signed magnitude along {@code direction} at {@code arm}
  * (same COM-relative-position / not-necessarily-unit-direction contract as {@link
  * #currentMaxDissipativeImpulseMagnitude}), mutating linear/angular velocity exactly as the
  * real native engine does ({@code v += impulse/mass}, {@code omega += inverseInertia*(arm x
  * impulse)}), and returns the REAL kinetic-energy change this single application caused,
  * measured against the actual state immediately before it - never a shared or stale snapshot.
  */
 double applyImpulseAndMeasureEnergyChange(Vector3dc arm, Vector3dc direction, double impulseMagnitude) {
  Vector3d unitDirection = normalizedOrThrow(direction);
  double keBefore = kineticEnergy();
  Vector3d impulse = new Vector3d(unitDirection).mul(impulseMagnitude);
  Vector3d angularImpulse = new Vector3d(arm).cross(impulse, new Vector3d());
  linearVelocity.add(new Vector3d(impulse).mul(massData.getInverseMass()));
  angularVelocity.add(massData.getInverseInertiaTensor().transform(angularImpulse, new Vector3d()));
  return kineticEnergy() - keBefore;
 }

 private static Vector3d normalizedOrThrow(Vector3dc direction) {
  double length = direction.length();
  if (!Double.isFinite(length) || length == 0) {
   throw new IllegalArgumentException("direction must be a finite, nonzero vector, got " + direction);
  }
  return new Vector3d(direction).normalize();
 }

 private double kineticEnergy() {
  Vector3d inertiaTimesOmega = massData.getInertiaTensor().transform(angularVelocity, new Vector3d());
  return 0.5 * massData.getMass() * linearVelocity.lengthSquared() + 0.5 * angularVelocity.dot(inertiaTimesOmega);
 }
}
