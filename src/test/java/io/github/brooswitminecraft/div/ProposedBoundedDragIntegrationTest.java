package io.github.brooswitminecraft.div;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MINECRAFT-545/547, John's comment 54700: tests for the UNWIRED production-integration design
 * {@link ProposedBoundedDragIntegration}. Not called from any production path.
 */
class ProposedBoundedDragIntegrationTest {
 private static final double DT = 0.05;

 @Test void aDragForceAlreadyWithinBoundIsReturnedUnchanged() {
  double mass = 400.0;
  MassData massData = TestMassData.solidBox(mass, new Vector3d(2.0, 1.0, 4.0));
  Vector3d arm = new Vector3d(1.5, -0.8, 3.0);
  Vector3d linearVelocity = new Vector3d(0, 43.90, 0); // +y, so a -y drag OPPOSES it (s<0)
  Vector3d angularVelocity = new Vector3d(0, 0, 0);
  // Opposing direction (-y) with a tiny magnitude - certainly within bound.
  Vector3d smallDrag = new Vector3d(0, -0.01, 0);

  Vector3d result = ProposedBoundedDragIntegration.boundedDragForce(massData, arm, smallDrag, linearVelocity, angularVelocity, DT);

  assertEquals(0, new Vector3d(smallDrag).sub(result).length(), 1e-12, "a drag force already well within bound must be returned unchanged");
 }

 @Test void aDragForceExceedingTheOpposingBoundIsScaledDownButKeepsItsDirection() {
  double mass = 400.0;
  MassData massData = TestMassData.solidBox(mass, new Vector3d(2.0, 1.0, 4.0));
  Vector3d arm = new Vector3d(1.5, -0.8, 3.0);
  // Arrange linear velocity so the point-velocity's y-component is negative (so a +y drag force
  // OPPOSES it - the dissipative case).
  Vector3d linearVelocity = new Vector3d(0, 43.90, 0);
  Vector3d angularVelocity = new Vector3d(0, 0, 0);
  Vector3d largeDrag = new Vector3d(0, -2_000_000.0, 0); // deliberately far past the real bound (~55798 impulse units here), opposing the +y velocity

  Vector3d result = ProposedBoundedDragIntegration.boundedDragForce(massData, arm, largeDrag, linearVelocity, angularVelocity, DT);

  assertTrue(result.length() < largeDrag.length(), "an over-bound drag force must be scaled down");
  assertTrue(result.y < 0, "direction must be preserved - still opposing +y");
  // Confirm the result's own impulse magnitude lands exactly at the forward dissipative bound.
  double resultImpulseMagnitude = result.length() * DT;
  Vector3d pointVelocity = new Vector3d(angularVelocity).cross(arm).add(linearVelocity);
  Vector3d direction = new Vector3d(largeDrag).normalize();
  double s = pointVelocity.dot(direction);
  double k = massData.getInverseNormalMass(new Vector3d(arm).add(massData.getCenterOfMass()), direction);
  double expectedBound = Buoyancy.maxDissipativeImpulseMagnitude(s, k);
  assertEquals(expectedBound, resultImpulseMagnitude, 1e-6, "the scaled result must land exactly at the forward dissipative bound");
 }

 @Test void aDragForceThatAidsRatherThanOpposesThePointVelocityIsScaledEntirelyToZero() {
  // The sign case the class doc calls out explicitly: when the drag direction AIDS (rather
  // than opposes) the point-velocity component along itself, ANY forward magnitude adds
  // energy - the bound must collapse to exactly zero, not merely "small".
  double mass = 400.0;
  MassData massData = TestMassData.solidBox(mass, new Vector3d(2.0, 1.0, 4.0));
  Vector3d arm = new Vector3d(1.5, -0.8, 3.0);
  Vector3d linearVelocity = new Vector3d(0, 43.90, 0); // +y point velocity
  Vector3d angularVelocity = new Vector3d(0, 0, 0);
  Vector3d aidingDrag = new Vector3d(0, 50.0, 0); // SAME sign as the velocity - aiding, not opposing

  Vector3d result = ProposedBoundedDragIntegration.boundedDragForce(massData, arm, aidingDrag, linearVelocity, angularVelocity, DT);

  assertEquals(0, result.length(), 1e-12, "a drag force that aids (not opposes) the point velocity must be scaled entirely to zero");
 }

 @Test void aZeroDragForceIsReturnedUnchanged() {
  double mass = 400.0;
  MassData massData = TestMassData.solidBox(mass, new Vector3d(2.0, 1.0, 4.0));
  Vector3d arm = new Vector3d(1.5, -0.8, 3.0);
  Vector3d result = ProposedBoundedDragIntegration.boundedDragForce(
    massData, arm, new Vector3d(0, 0, 0), new Vector3d(43.90, 0, 0), new Vector3d(0, 0, 0), DT);
  assertEquals(0, result.length(), 1e-12);
 }

 @Test void scalingNeverProducesAMagnitudeLargerThanThePhysicalForceItself() {
  // Sanity bound on the scale factor itself - never boosts, regardless of inputs.
  double mass = 250.0;
  MassData massData = TestMassData.solidBox(mass, new Vector3d(3.0, 0.75, 1.5));
  Vector3d arm = new Vector3d(-2.2, 1.1, -0.9);
  Vector3d linearVelocity = new Vector3d(-12.0, 59.09, 20.15);
  Vector3d angularVelocity = new Vector3d(1.5, -2.0, 0.8);
  Vector3d drag = new Vector3d(0.4, 0.6, -0.69).normalize().mul(37.2);

  Vector3d result = ProposedBoundedDragIntegration.boundedDragForce(massData, arm, drag, linearVelocity, angularVelocity, DT);

  assertTrue(result.length() <= drag.length() + 1e-9, "the result's magnitude must never exceed the physical force's own magnitude");
 }
}
