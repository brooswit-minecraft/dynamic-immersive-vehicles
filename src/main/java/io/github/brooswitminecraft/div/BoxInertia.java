package io.github.brooswitminecraft.div;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import org.joml.Matrix3d;
import org.joml.Matrix3dc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * MINECRAFT-545/547 (John's comments 54403/54407): the standard solid-rectangular-box inertia
 * tensor about its own geometric center, for a given mass and HALF-extents (hx, hy, hz) - the
 * exact quantity {@link dev.ryanhcode.sable.api.physics.object.box.BoxPhysicsObject}'s own
 * legacy scalar-mass constructor does NOT compute (confirmed via {@code javap} on the actually
 * pinned {@code sable-neoforge-1.21.1-2.0.5.jar}: its private {@code BoxMassData} unconditionally
 * returns {@code mass/6} on every axis, with zero dependency on the box's own half-extents).
 *
 * <p>Formula: for a solid cuboid of FULL side lengths (Lx, Ly, Lz), the standard inertia about
 * its own center is {@code I_xx = m/12*(Ly^2+Lz^2)} (and the cyclic equivalents for Y/Z). With
 * half-extents {@code hx=Lx/2}, {@code Ly^2 = 4*hy^2}, giving {@code I_xx = m/3*(hy^2+hz^2)} -
 * this is a standard, textbook rigid-body mechanics result, not a DIV-specific invention. It
 * is a CHOSEN model (a uniform-density solid box), not a proven match to this hull's actual
 * mass distribution - no such measurement exists. This is explicitly a different model from
 * Sable's own {@code mass/6} fallback, not merely a correction to the SAME one: {@code mass/6}
 * has no shape term in it at all (not uniform-density, not point-mass - a true point mass has
 * ZERO rotational inertia, which {@code mass/6} is not either) - it is simply shape-ignorant,
 * with no coherent physical model behind it.
 */
final class BoxInertia {
 private BoxInertia() {}

 static Matrix3d solidBoxTensor(double mass, Vector3dc halfExtents) {
  double hx = halfExtents.x(), hy = halfExtents.y(), hz = halfExtents.z();
  double ixx = mass / 3.0 * (hy * hy + hz * hz);
  double iyy = mass / 3.0 * (hx * hx + hz * hz);
  double izz = mass / 3.0 * (hx * hx + hy * hy);
  return new Matrix3d(ixx, 0, 0, 0, iyy, 0, 0, 0, izz);
 }

 /**
  * A real {@code MassData} wrapping {@link #solidBoxTensor} - for callers (MINECRAFT-545/547,
  * John's comment 54706) that need the full {@code MassData} interface (e.g.
  * {@code MassData.getInverseNormalMass}), not merely the raw tensor. Center of mass at the
  * origin (the box's own geometric center), matching this ticket's existing assumption
  * throughout.
  * <p>CONFIRMED (not merely chosen) to match Rapier's own real native computation for the
  * {@code ColliderBuilder::mass(mass)} creation path {@code createBox} actually uses - traced
  * through the exact pinned dependency chain to upstream {@code parry3d 0.26.0}'s own cuboid
  * mass-properties source; see VERIFICATION.md "Corrections per 54703" for the full derivation.
  * Rapier's own native computation runs in single precision ({@code f32}, its own crate's
  * default feature) - this is a formula match, not a bit-identical numeric one (54706).
  */
 static MassData solidBoxMassData(double mass, Vector3dc halfExtents) {
  Matrix3dc inertia = solidBoxTensor(mass, halfExtents);
  Matrix3dc inverseInertia = new Matrix3d(inertia).invert();
  double inverseMass = 1.0 / mass;
  return new MassData() {
   public double getMass() { return mass; }
   public double getInverseMass() { return inverseMass; }
   public Matrix3dc getInertiaTensor() { return inertia; }
   public Matrix3dc getInverseInertiaTensor() { return inverseInertia; }
   public Vector3dc getCenterOfMass() { return new Vector3d(0, 0, 0); }
  };
 }
}
