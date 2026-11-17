package io.github.brooswitminecraft.div;
import org.joml.Matrix3d;
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
}
