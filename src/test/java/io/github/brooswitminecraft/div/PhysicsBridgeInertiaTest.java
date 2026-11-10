package io.github.brooswitminecraft.div;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@code PhysicsBridge.solidBoxInertia} and {@code PhysicsBridge.inertiaCorrection} -
 * the shared ground-support/rollover root cause and its fix. Confirmed against the exact pinned
 * dependency, {@code sable-neoforge-1.21.1:2.0.5}: {@code BoxPhysicsObject}'s mass data hard-codes
 * an ISOTROPIC {@code mass/6} inertia tensor regardless of the box's actual half-extents (there is
 * no constructor in this Sable version that accepts a custom tensor), so any vehicle whose real
 * cross-section differs from the implied 1m-half-extent cube reference gets the wrong rotational
 * resistance from Sable - under-resisting roll for every realistically-sized ground vehicle, the
 * mechanism behind "flips way too easily." These two methods are the exact seam
 * {@code Body.stepGround} uses to compute what the vehicle's real inertia should be and the
 * compensating angular-velocity delta to apply on top of Sable's already-applied (wrong,
 * isotropic) response - testable without a live Sable runtime.
 */
class PhysicsBridgeInertiaTest {
 @Test void referenceCubeMatchesSablesOwnIsotropicAssumption() {
  // halfExtents (0.5,0.5,0.5) is exactly the 1m cube BoxMassData's mass/6 formula implies
  // (mass/6 = mass/3*(0.5^2+0.5^2)) - solidBoxInertia must reduce to that same isotropic value
  // here, and inertiaCorrection must then have nothing left to correct.
  var inertia = PhysicsBridge.solidBoxInertia(12, new Vector3d(.5,.5,.5));
  assertEquals(2, inertia.m00(), 1e-9);
  assertEquals(2, inertia.m11(), 1e-9);
  assertEquals(2, inertia.m22(), 1e-9);
  assertEquals(0, inertia.m01(), 1e-9);
  assertEquals(0, inertia.m02(), 1e-9);
  assertEquals(0, inertia.m12(), 1e-9);
  var correction = PhysicsBridge.inertiaCorrection(12, new Vector3d(.5,.5,.5), new Vector3d(3,-4,5));
  assertEquals(0, correction.x, 1e-9);
  assertEquals(0, correction.y, 1e-9);
  assertEquals(0, correction.z, 1e-9);
 }
 @Test void inertiaScalesWithEachHalfExtentNotJustMass() {
  // Sable's own bug this fixes is that its isotropic mass/6 tensor is identical regardless of
  // size - a narrow motorcycle-width ATV and a wide flatbed truck of the same mass would get the
  // exact same rotational resistance. A non-cubic box must produce a non-isotropic (and
  // differently-scaled) tensor instead, scaling with the OTHER two half-extents per axis the way
  // a real chassis's mass distribution would.
  var inertia = PhysicsBridge.solidBoxInertia(6, new Vector3d(1,.5,2));
  assertEquals(8.5, inertia.m00(), 1e-9, "roll axis depends on height and length, not width");
  assertEquals(10, inertia.m11(), 1e-9, "pitch axis depends on width and length, not height");
  assertEquals(2.5, inertia.m22(), 1e-9, "yaw axis depends on width and height, not length");
 }
 @Test void correctionUndoesSablesIsotropicResponseAndSubstitutesTheRealOne() {
  // Picking the torque impulse per axis equal to that axis's real inertia makes the real
  // angular-velocity response exactly 1 rad/s - the correction is then just "1 minus whatever
  // Sable's fixed isotropic inverse already produced," isolating the fix's own arithmetic from
  // solidBoxInertia's.
  var halfExtents = new Vector3d(1,.5,2);
  double mass = 6, isotropicInverse = 6.0/mass;
  var correction = PhysicsBridge.inertiaCorrection(mass, halfExtents, new Vector3d(8.5,10,2.5));
  assertEquals(1-8.5*isotropicInverse, correction.x, 1e-9);
  assertEquals(1-10*isotropicInverse, correction.y, 1e-9);
  assertEquals(1-2.5*isotropicInverse, correction.z, 1e-9);
  // A bigger-than-reference box always gets LESS rotational response than Sable alone would give
  // it (isotropicInverse=1 > each real inverse here) - the correction must always subtract,
  // never add, exactly the "under-resists roll" direction of the bug this fixes.
  assertTrue(correction.x < 0 && correction.y < 0 && correction.z < 0);
 }
 @Test void smallerThanReferenceBoxWouldBeCorrectedUpward() {
  // Symmetry check the other way: a box smaller than the implicit reference cube has MORE real
  // inverse-inertia than Sable's isotropic one, so the correction must add response, not just
  // always subtract - proving this is a genuine two-sided physical correction, not a one-way damp.
  var correction = PhysicsBridge.inertiaCorrection(12, new Vector3d(.2,.2,.2), new Vector3d(1,0,0));
  assertTrue(correction.x > 0);
 }
}
