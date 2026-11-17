package io.github.brooswitminecraft.div;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * UNWIRED diagnostic/proposal utility (MINECRAFT-545/547, John's comment 54697). Not called
 * from {@code BuoyantForces.apply} or any other production path - no behavior change.
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
 * <p>This rules out both prior multi-float models: summing {@code impulseEnergyChange} per
 * impulse against one shared snapshot (confirmed wrong by John's 54694 counter-example) and
 * calling it once with every float's impulse combined into a single scalar {@code J} against a
 * single {@code k} (wrong too, per 54697 - different floats act at different points/directions,
 * each with its own effective mass; there is no one {@code k} that represents them jointly).
 * The physically correct model, matching what the native engine actually does, is SEQUENTIAL:
 * apply each float's impulse as a real linear+angular velocity update, in dispatch order,
 * bounding each one against the CURRENT (already-updated) state at the moment it is applied -
 * never a stale snapshot (the exact gap John's comment 54691 originally named).
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
  * The dissipative-impulse-magnitude bound at THIS point/direction, evaluated against the
  * ledger's CURRENT state - i.e. already reflecting every impulse applied so far in this
  * sweep, not a stale pre-tick snapshot. Calling this again after {@link
  * #applyImpulseAndMeasureEnergyChange} for an earlier float gives a genuinely different
  * (updated) answer, by design - that is the entire point of the sequential model.
  */
 double currentMaxDissipativeImpulseMagnitude(Vector3dc arm, Vector3dc direction) {
  Vector3d pointVelocity = new Vector3d(angularVelocity).cross(arm, new Vector3d()).add(linearVelocity);
  double s = pointVelocity.dot(direction);
  double k = massData.getInverseNormalMass(arm, direction);
  return Buoyancy.maxDissipativeImpulseMagnitude(s, k);
 }

 /**
  * Applies one impulse of the given signed magnitude along {@code direction} at {@code arm},
  * mutating linear/angular velocity exactly as the real native engine does
  * ({@code v += impulse/mass}, {@code omega += inverseInertia*(arm x impulse)}), and returns
  * the REAL kinetic-energy change this single application caused, measured against the actual
  * state immediately before it - never a shared or stale snapshot.
  */
 double applyImpulseAndMeasureEnergyChange(Vector3dc arm, Vector3dc direction, double impulseMagnitude) {
  double keBefore = kineticEnergy();
  Vector3d impulse = new Vector3d(direction).mul(impulseMagnitude);
  Vector3d angularImpulse = new Vector3d(arm).cross(impulse, new Vector3d());
  linearVelocity.add(new Vector3d(impulse).mul(massData.getInverseMass()));
  angularVelocity.add(massData.getInverseInertiaTensor().transform(angularImpulse, new Vector3d()));
  return kineticEnergy() - keBefore;
 }

 private double kineticEnergy() {
  Vector3d inertiaTimesOmega = massData.getInertiaTensor().transform(angularVelocity, new Vector3d());
  return 0.5 * massData.getMass() * linearVelocity.lengthSquared() + 0.5 * angularVelocity.dot(inertiaTimesOmega);
 }
}
