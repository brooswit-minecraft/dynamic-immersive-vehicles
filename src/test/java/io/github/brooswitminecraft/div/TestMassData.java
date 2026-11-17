package io.github.brooswitminecraft.div;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import org.joml.Matrix3d;
import org.joml.Matrix3dc;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * MINECRAFT-545/547, John's comments 54697/54700: test-only support for exercising the REAL
 * {@code MassData.getInverseNormalMass} default method directly (instead of hand-deriving its
 * cross/dot formula inline, which a prior test version did and which 54697 asked be corrected).
 * Builds real solid-box {@code MassData} instances using the SAME formula as
 * {@link BoxInertia#solidBoxTensor} (mass/3, not Sable's own shape-ignorant mass/6 fallback).
 * <p>CAVEAT (54700): this is a CHOSEN uniform-density solid-box model - not confirmed to equal
 * what Rapier itself actually computes internally for a cuboid collider at native creation time
 * (54413 already established Rapier computes its own mass properties independently of Sable's
 * Java-side {@code MassData} for that specific path). Energy predictions made using these
 * instances are only as good as that unconfirmed equivalence.
 */
final class TestMassData {
 private TestMassData() {}

 static MassData solidBox(double mass, Vector3dc halfExtents) {
  return solidBoxWithCenterOfMass(mass, halfExtents, new Vector3d(0, 0, 0));
 }

 /** Same solid-box model as {@link #solidBox}, but with a non-origin center of mass - exists
  * so tests can exercise {@code MassData.getInverseNormalMass}'s own internal COM subtraction
  * against a case where it actually matters (54700: a prior {@link SequentialImpulseEnergyLedger}
  * bug passed its COM-relative {@code arm} straight through as a position, which only happened
  * to be correct because every existing test used a zero COM). */
 static MassData solidBoxWithCenterOfMass(double mass, Vector3dc halfExtents, Vector3dc centerOfMass) {
  Matrix3dc inertia = BoxInertia.solidBoxTensor(mass, halfExtents);
  Matrix3dc inverseInertia = new Matrix3d(inertia).invert();
  Vector3d com = new Vector3d(centerOfMass);
  return new MassData() {
   public double getMass() { return mass; }
   public double getInverseMass() { return 1.0 / mass; }
   public Matrix3dc getInertiaTensor() { return inertia; }
   public Matrix3dc getInverseInertiaTensor() { return inverseInertia; }
   public Vector3dc getCenterOfMass() { return com; }
  };
 }

 /** The SAME solid-box model as {@link #solidBox}, but with its inertia tensor expressed in a
  * WORLD frame rotated by {@code rotation} relative to the box's own body-local principal axes
  * (i.e. {@code I_world = R * I_local * R^T}) - a genuinely rotated, generally non-diagonal
  * tensor, for the frame-covariance test 54700 asked for (a prior test only varied the impulse
  * DIRECTION against a still-diagonal, unrotated tensor). Center of mass stays at the origin. */
 static MassData rotatedSolidBox(double mass, Vector3dc halfExtents, Quaterniondc rotation) {
  Matrix3d local = BoxInertia.solidBoxTensor(mass, halfExtents);
  Matrix3d rotationMatrix = new Matrix3d().set(rotation);
  Matrix3d rotationTranspose = new Matrix3d(rotationMatrix).transpose();
  Matrix3dc worldInertia = rotationMatrix.mul(local).mul(rotationTranspose, new Matrix3d());
  Matrix3dc worldInverseInertia = new Matrix3d(worldInertia).invert();
  return new MassData() {
   public double getMass() { return mass; }
   public double getInverseMass() { return 1.0 / mass; }
   public Matrix3dc getInertiaTensor() { return worldInertia; }
   public Matrix3dc getInverseInertiaTensor() { return worldInverseInertia; }
   public Vector3dc getCenterOfMass() { return new Vector3d(0, 0, 0); }
  };
 }
}
