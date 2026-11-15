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

 @Test void hookupOnlyRotationComposesWithNativeSignNotInverted() {
  // Review 53832/task-review-opus 53835's own worked example: a hookup-only +30-degree rot,
  // identity hitch rot, identity towing orientation. IV's native composition (decompiled
  // EntityVehicleF_Physics.getForcesAndMotions) is Q_towed = Q_towing * R_hitch * R_hookup =
  // identity * identity * R_hookup = R_hookup (plain +30 degrees). Sable's own joint enforces
  // Q_towed = Q_towing * local_frame1 * inverse(local_frame2) - PhysicsBridge.createHitch
  // passes local_frame1 = R_hitch (identity here) and local_frame2 = inverse(R_hookup), so
  // Sable's formula collapses to identity * identity * inverse(inverse(R_hookup)) = R_hookup -
  // the correct +30, not the -30 a naive direct pass-through of R_hookup would have produced.
  var hookupRot = new RotationMatrix().rotateY(30);
  var localFrame1 = HitchJoint.localJointFrame(null);
  var localFrame2 = HitchJoint.localJointFrame(hookupRot).invert();
  var sableEnforced = new Quaterniond(localFrame1).mul(new Quaterniond(localFrame2).invert());
  var expectedNative = HitchJoint.localJointFrame(hookupRot);
  double angleDiff = new Quaterniond(sableEnforced).invert().mul(expectedNative).angle();
  assertEquals(0, angleDiff, 1e-9);
 }

 @Test void nonIdentityRotationsOnBothEndsMatchNativeComposition() {
  // Same derivation as above, generalized to non-identity towing/hitch/hookup rotations on
  // all three (not just the single-axis illustrative case) - the sign-distinguishing coverage
  // review 53832 asked for beyond the one worked example.
  var towingRot = new RotationMatrix().rotateY(15).rotateX(10);
  var hitchRot = new RotationMatrix().rotateZ(-8);
  var hookupRot = new RotationMatrix().rotateY(30).rotateX(5);

  var qTowing = HitchJoint.localJointFrame(towingRot);
  var localFrame1 = HitchJoint.localJointFrame(hitchRot);
  var localFrame2 = HitchJoint.localJointFrame(hookupRot).invert();

  var sableEnforced = new Quaterniond(qTowing).mul(new Quaterniond(localFrame1)).mul(new Quaterniond(localFrame2).invert());
  var native_ = new Quaterniond(qTowing).mul(HitchJoint.localJointFrame(hitchRot)).mul(HitchJoint.localJointFrame(hookupRot));

  double angleDiff = new Quaterniond(sableEnforced).invert().mul(native_).angle();
  assertEquals(0, angleDiff, 1e-6);
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

 // MINECRAFT-530: composing an installed APart's own position/rotation/scale into the
 // vehicle-root-local frame createHitch needs - the gap MINECRAFT-525 explicitly left
 // unverified/unsupported (see HitchJoint.partConnectionOffset's own doc comment).

 @Test void partConnectionOffsetCollapsesToRawPosWithIdentityPartFrame() {
  // A vehicle-root-defined connection: identity orientation, zero offset, scale 1 - must be a
  // no-op, exactly the raw pos createHitch always used pre-MINECRAFT-530.
  var connectionLocalPos = new Vector3d(1, 0, -3);
  var result = HitchJoint.partConnectionOffset(new Vector3d(), new RotationMatrix(), new Vector3d(1, 1, 1), connectionLocalPos, new Vector3d());
  assertEquals(new Vector3d(1, 0, -3), result);
 }

 @Test void partConnectionOffsetAppliesPartScaleBeforeOffset() {
  // A part scaled 2x on X only, installed at the vehicle's own origin with no extra rotation.
  var result = HitchJoint.partConnectionOffset(new Vector3d(), new RotationMatrix(), new Vector3d(2, 1, 1),
    new Vector3d(3, 0, 0), new Vector3d());
  assertEquals(new Vector3d(6, 0, 0), result, "connection pos scales with the part's own full accumulated scale");
 }

 @Test void partConnectionOffsetTranslatesByPartLocalOffset() {
  // An unscaled, unrotated part installed 5 units forward of the vehicle's own origin.
  var result = HitchJoint.partConnectionOffset(new Vector3d(5, 0, 0), new RotationMatrix(), new Vector3d(1, 1, 1),
    new Vector3d(0, 0, -2), new Vector3d());
  assertEquals(new Vector3d(5, 0, -2), result);
 }

 @Test void partConnectionOffsetRotatesScaledOffsetByPartOrientationBeforeTranslating() {
  // Same +90-degree yaw convention as localJointFrameConvertsAnAuthoredRotation (local +Z to
  // +X), applied to the part's own installed orientation this time, with a non-trivial scale
  // and offset too - demonstrating translated/rotated/scaled composed together, not in
  // isolation.
  var partOrientation = new RotationMatrix().rotateY(90);
  var result = HitchJoint.partConnectionOffset(new Vector3d(10, 0, 0), partOrientation, new Vector3d(2, 1, 1),
    new Vector3d(0, 0, 1), new Vector3d());
  // Scale by (2,1,1) first: (0,0,1) unaffected on Z -> (0,0,1); rotate +90 yaw: local +Z -> +X ->
  // (1,0,0); translate by the part's own vehicle-root-local offset (10,0,0) -> (11,0,0).
  assertEquals(11, result.x, 1e-9);
  assertEquals(0, result.y, 1e-9);
  assertEquals(0, result.z, 1e-9);
 }

 @Test void partConnectionOffsetUsesTheSameDestInstanceItReturns() {
  var dest = new Vector3d();
  var result = HitchJoint.partConnectionOffset(new Vector3d(), new RotationMatrix(), new Vector3d(1, 1, 1), new Vector3d(), dest);
  assertSame(dest, result);
 }

 @Test void partConnectionRotationCollapsesToPlainConversionWithIdentityPartOrientation() {
  var hookupRot = new RotationMatrix().rotateY(30);
  var result = HitchJoint.partConnectionRotation(new RotationMatrix(), hookupRot);
  assertEquals(HitchJoint.localJointFrame(hookupRot), result);
 }

 @Test void partConnectionRotationComposesPartOrientationBeforeConnectionRot() {
  // The part's own installed orientation (+90 yaw) must be applied FIRST, with the connection's
  // own authored rot (+30 yaw) composed after it - not the other way around, and not just the
  // connection's rot alone. Transforming local +Z should land where a +90-then-+30 (120 total)
  // yaw of +Z would: local +Z rotated 120 degrees about Y.
  var partOrientation = new RotationMatrix().rotateY(90);
  var connectionRot = new RotationMatrix().rotateY(30);
  var composed = HitchJoint.partConnectionRotation(partOrientation, connectionRot);
  var expected = HitchJoint.localJointFrame(new RotationMatrix().rotateY(120));
  double angleDiff = new Quaterniond(composed).invert().mul(expected).angle();
  assertEquals(0, angleDiff, 1e-9);
  // And it must differ from using the bare connection rot alone (would wrongly drop the part's
  // own +90 yaw entirely).
  double angleDiffFromBareConnectionRot = new Quaterniond(composed).invert().mul(HitchJoint.localJointFrame(connectionRot)).angle();
  assertTrue(angleDiffFromBareConnectionRot > 1e-6);
 }
}
