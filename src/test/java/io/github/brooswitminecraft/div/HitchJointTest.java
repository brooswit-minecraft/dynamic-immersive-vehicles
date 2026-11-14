package io.github.brooswitminecraft.div;
import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import minecrafttransportsimulator.baseclasses.RotationMatrix;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure geometry/axis-selection for a trailer hitch, with no live entity, world, or Sable
 * runtime: the BODY-LOCAL anchor offset PhysicsBridge must hand Sable for a hitch joint
 * (MINECRAFT-525: confirmed by the real native source and the empirical anchor-frame probe -
 * see HitchJoint's own doc comment), and which degrees of freedom IV's own connection flags
 * imply should be locked.
 */
class HitchJointTest {
 @Test void anchorOffsetIsLocalOffsetMinusCenter() {
  var center = new Vector3d(1, 0, 0);
  var localOffset = new Vector3d(1, 0, -3); // IV's typical rear hitch point: behind the origin
  var result = HitchJoint.anchorLocalOffset(center, localOffset, new Vector3d());
  assertEquals(new Vector3d(0, 0, -3), result);
 }

 @Test void anchorOffsetIgnoresNonCenterGeometry() {
  // No orientation or world-position parameter exists at all any more (MINECRAFT-525): the
  // anchor stays a pure body-local offset - only the local offset and the body's own center
  // ever factor in.
  var center = new Vector3d();
  var localOffset = new Vector3d(0, 0, -3);
  var result = HitchJoint.anchorLocalOffset(center, localOffset, new Vector3d());
  assertEquals(0, result.x, 1e-9);
  assertEquals(0, result.y, 1e-9);
  assertEquals(-3, result.z, 1e-9);
 }

 @Test void anchorUsesTheSameDestInstanceItReturns() {
  var dest = new Vector3d();
  var result = HitchJoint.anchorLocalOffset(new Vector3d(), new Vector3d(), dest);
  assertSame(dest, result);
 }

 @Test void localJointFrameIsIdentityWhenRotIsUnset() {
  // JSONConnection.rot's own documented default/no-op: "has no effect" when the connection is
  // not mounted/restricted, and an unset rot (null - not every connection authors one) must
  // still mean "impose no extra twist" rather than NPE or some other default.
  var identity = HitchJoint.localJointFrame(null);
  assertEquals(new Quaterniond(), identity);
 }

 @Test void localJointFrameConvertsAnAuthoredRotation() {
  // A 90-degree yaw (same RotationMatrix.rotateY IV itself uses) starting from IV's own
  // identity (RotationMatrix's constructor already calls setToZero(), which - despite the
  // name - sets the IDENTITY matrix, not zero). Independently worked out from the exact
  // RotationMatrix->Quaterniond index mapping PhysicsBridge.Body's constructor already
  // established (not merely restating HitchJoint's own implementation): the resulting
  // quaternion must carry local +Z to +X.
  var rot = new RotationMatrix().rotateY(90);
  var quaternion = HitchJoint.localJointFrame(rot);
  var transformed = quaternion.transform(new Vector3d(0, 0, 1));
  assertEquals(1, transformed.x, 1e-9);
  assertEquals(0, transformed.y, 1e-9);
  assertEquals(0, transformed.z, 1e-9);
 }

 @Test void ordinaryConnectionLocksOnlyLinearAxes() {
  var axes = HitchJoint.lockedAxes(false, false);
  assertEquals(java.util.Set.of(ConstraintJointAxis.LINEAR_X, ConstraintJointAxis.LINEAR_Y, ConstraintJointAxis.LINEAR_Z), axes);
 }

 @Test void mountedConnectionLocksEveryAxis() {
  var axes = HitchJoint.lockedAxes(true, false);
  assertEquals(java.util.Set.of(ConstraintJointAxis.values()), axes);
 }

 @Test void restrictedConnectionLeavesOnlyPitchFree() {
  // JSONConnection.restricted's own documented contract: "restricted to only change in pitch,
  // and won't be allowed to yaw back and forth" - so yaw (Y) and roll (Z) are locked, pitch (X)
  // stays free, alongside the always-locked linear axes.
  var axes = HitchJoint.lockedAxes(false, true);
  assertEquals(java.util.Set.of(ConstraintJointAxis.LINEAR_X, ConstraintJointAxis.LINEAR_Y, ConstraintJointAxis.LINEAR_Z,
    ConstraintJointAxis.ANGULAR_Y, ConstraintJointAxis.ANGULAR_Z), axes);
  assertFalse(axes.contains(ConstraintJointAxis.ANGULAR_X));
 }

 @Test void mountedTakesPrecedenceOverRestricted() {
  assertEquals(java.util.Set.of(ConstraintJointAxis.values()), HitchJoint.lockedAxes(true, true));
 }
}
