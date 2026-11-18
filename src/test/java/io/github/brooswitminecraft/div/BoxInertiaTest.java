package io.github.brooswitminecraft.div;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MINECRAFT-545/547 (John's comments 54403/54407): pure-math verification of the standard
 * solid-box inertia formula {@link BoxInertia} supplies - no Sable/native runtime involved.
 */
class BoxInertiaTest {
 @Test void cubeMatchesTheStandardCubeFormula() {
  // A cube of half-extent h has, by the general formula, I = m/3*(h^2+h^2) = 2*m*h^2/3 on
  // every axis - cross-checked here against the box formula collapsing to equal half-extents,
  // not merely re-asserting the same code path.
  double mass = 12.0, h = 0.5;
  var tensor = BoxInertia.solidBoxTensor(mass, new Vector3d(h, h, h));
  double expected = 2.0 * mass * h * h / 3.0;
  assertEquals(expected, tensor.m00(), 1e-9);
  assertEquals(expected, tensor.m11(), 1e-9);
  assertEquals(expected, tensor.m22(), 1e-9);
 }

 @Test void offDiagonalTermsAreZeroForAnAxisAlignedBox() {
  var tensor = BoxInertia.solidBoxTensor(50.0, new Vector3d(0.3, 0.7, 1.1));
  assertEquals(0, tensor.m01(), 1e-12); assertEquals(0, tensor.m02(), 1e-12);
  assertEquals(0, tensor.m10(), 1e-12); assertEquals(0, tensor.m12(), 1e-12);
  assertEquals(0, tensor.m20(), 1e-12); assertEquals(0, tensor.m21(), 1e-12);
 }

 @Test void matchesTheExactFixtureHullUsedThroughoutThisInvestigation() {
  // mass=400kg, half-extents (0.6, 0.3, 1.0) - BuoyantHitchProbe's own HALF_EXTENT_X/
  // HALF_HEIGHT_Y/HALF_EXTENT_Z and run/config's divtest:buoyanthitch-* massKg. Hand-computed
  // in VERIFICATION.md (54405): X=145.33..., Y=181.33..., Z=60.0 kg*m^2 - cross-checked here,
  // not merely restated.
  var tensor = BoxInertia.solidBoxTensor(400.0, new Vector3d(0.6, 0.3, 1.0));
  assertEquals(400.0 / 3.0 * (0.3 * 0.3 + 1.0 * 1.0), tensor.m00(), 1e-9);
  assertEquals(400.0 / 3.0 * (0.6 * 0.6 + 1.0 * 1.0), tensor.m11(), 1e-9);
  assertEquals(400.0 / 3.0 * (0.6 * 0.6 + 0.3 * 0.3), tensor.m22(), 1e-9);
  assertEquals(60.0, tensor.m22(), 1e-9);
 }

 @Test void scalesLinearlyWithMass() {
  var tensor1 = BoxInertia.solidBoxTensor(10.0, new Vector3d(0.4, 0.5, 0.6));
  var tensor2 = BoxInertia.solidBoxTensor(20.0, new Vector3d(0.4, 0.5, 0.6));
  assertEquals(tensor1.m00() * 2, tensor2.m00(), 1e-9);
  assertEquals(tensor1.m11() * 2, tensor2.m11(), 1e-9);
  assertEquals(tensor1.m22() * 2, tensor2.m22(), 1e-9);
 }
}
