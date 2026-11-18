package io.github.brooswitminecraft.div;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.api.physics.object.box.BoxPhysicsObject;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import org.joml.Matrix3d;
import org.joml.Matrix3dc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * MINECRAFT-545/547 (John's comments 54403/54407/54413/54419): a drop-in {@code
 * BoxPhysicsObject} subclass that overrides {@code getMassTracker()} to report a chosen,
 * shape-aware solid-box inertia tensor ({@link BoxInertia#solidBoxTensor}) instead of the base
 * class's own legacy {@code mass/6} fallback (confirmed via {@code javap} on the pinned {@code
 * sable-neoforge-1.21.1-2.0.5.jar}: {@code BoxPhysicsObject.getMassTracker()} and the class
 * itself are both {@code public}, non-{@code final} - this override uses only ordinary,
 * supported Java inheritance over Sable's own public API, with no reflection, no native patch,
 * and no change to the pinned dependency version.
 *
 * <p><b>Corrected scope (54413):</b> this is JAVA-SIDE METADATA only. Reading the pinned native
 * source directly (see VERIFICATION.md) confirmed {@code getMassTracker()} is NEVER consulted
 * by the actual native body-creation path ({@code createBox} takes raw mass/half-extents and
 * lets Rapier compute its own internal mass properties) - so this override does NOT change
 * "how resistant to rotation the native solver is told [the body] is," or anything else about
 * actual native simulation. It changes only what a JAVA caller observes from
 * {@code getMassTracker()}. It is not currently known to be, or claimed to be, a fix for
 * anything.
 *
 * <p>Everything else (collision geometry, pose, handle lifecycle, the actual native rigid body
 * creation/stepping) is inherited UNCHANGED from {@code BoxPhysicsObject} - only the Java-level
 * {@code getMassTracker()} return value differs. Center of mass stays at the box's own
 * geometric center (origin in the box's local frame), matching the base class's own implicit
 * assumption for the legacy constructor - this does not change WHERE the box is.
 */
final class ShapeAwareBoxPhysicsObject extends BoxPhysicsObject {
 private final MassData massData;

 ShapeAwareBoxPhysicsObject(Pose3dc pose, Vector3dc halfExtents, double mass) {
  super(pose, halfExtents, mass);
  Matrix3d inertia = BoxInertia.solidBoxTensor(mass, halfExtents);
  Matrix3d inverseInertia = inertia.invert(new Matrix3d());
  double inverseMass = 1.0 / mass;
  Vector3d centerOfMass = new Vector3d();
  this.massData = new MassData() {
   @Override public double getMass() { return mass; }
   @Override public double getInverseMass() { return inverseMass; }
   @Override public Matrix3dc getInertiaTensor() { return inertia; }
   @Override public Matrix3dc getInverseInertiaTensor() { return inverseInertia; }
   @Override public Vector3dc getCenterOfMass() { return centerOfMass; }
  };
 }

 @Override
 public MassData getMassTracker() { return massData; }
}
