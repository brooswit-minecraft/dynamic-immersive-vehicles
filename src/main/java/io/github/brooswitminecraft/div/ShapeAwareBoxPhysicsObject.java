package io.github.brooswitminecraft.div;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.api.physics.object.box.BoxPhysicsObject;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import org.joml.Matrix3d;
import org.joml.Matrix3dc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * MINECRAFT-545/547 (John's comments 54403/54407): a drop-in {@code BoxPhysicsObject} subclass
 * that overrides {@code getMassTracker()} to report the real, shape-aware solid-box inertia
 * tensor ({@link BoxInertia#solidBoxTensor}) instead of the base class's own legacy {@code
 * mass/6} fallback (confirmed via {@code javap} on the pinned {@code
 * sable-neoforge-1.21.1-2.0.5.jar}: {@code BoxPhysicsObject.getMassTracker()} and the class
 * itself are both {@code public}, non-{@code final} - this override uses only ordinary,
 * supported Java inheritance over Sable's own public API, with no reflection, no native patch,
 * and no change to the pinned dependency version.
 *
 * <p>Everything else (collision geometry, pose, handle lifecycle, the actual native rigid body
 * creation/stepping) is inherited UNCHANGED from {@code BoxPhysicsObject} - only the reported
 * mass/inertia differs. Center of mass stays at the box's own geometric center (origin in the
 * box's local frame), matching the base class's own implicit assumption for the legacy
 * constructor - this does not change WHERE the box is, only how resistant to rotation the
 * native solver is told it is.
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
