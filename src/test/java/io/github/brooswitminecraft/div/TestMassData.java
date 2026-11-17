package io.github.brooswitminecraft.div;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import org.joml.Matrix3d;
import org.joml.Matrix3dc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * MINECRAFT-545/547, John's comment 54697: test-only support for exercising the REAL
 * {@code MassData.getInverseNormalMass} default method directly (instead of hand-deriving its
 * cross/dot formula inline, which a prior test version did and which 54697 asked be corrected).
 * Builds a real solid-box {@code MassData} using the SAME formula as {@link BoxInertia#solidBoxTensor}
 * (mass/3, not Sable's own shape-ignorant mass/6 fallback).
 */
final class TestMassData {
 private TestMassData() {}

 static MassData solidBox(double mass, Vector3dc halfExtents) {
  Matrix3dc inertia = BoxInertia.solidBoxTensor(mass, halfExtents);
  Matrix3dc inverseInertia = new Matrix3d(inertia).invert();
  return new MassData() {
   public double getMass() { return mass; }
   public double getInverseMass() { return 1.0 / mass; }
   public Matrix3dc getInertiaTensor() { return inertia; }
   public Matrix3dc getInverseInertiaTensor() { return inverseInertia; }
   public Vector3dc getCenterOfMass() { return new Vector3d(0, 0, 0); }
  };
 }
}
