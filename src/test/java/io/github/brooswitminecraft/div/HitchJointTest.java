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

 // Shorthand matching a part installed directly on the vehicle (no nesting): identity vehicle
 // orientation means vehicleRootLocalOrientation(IDENTITY, partOrientation) == localJointFrame
 // (partOrientation) exactly - see vehicleRootLocalOrientation's own doc comment.
 private static final RotationMatrix IDENTITY = new RotationMatrix();
 private static Quaterniond topLevelPartOrientation(RotationMatrix partOrientation) {
  return HitchJoint.vehicleRootLocalOrientation(IDENTITY, partOrientation);
 }

 @Test void partConnectionOffsetCollapsesToRawPosWithIdentityPartFrame() {
  // A vehicle-root-defined connection: identity orientation, zero offset, scale 1 - must be a
  // no-op, exactly the raw pos createHitch always used pre-MINECRAFT-530.
  var connectionLocalPos = new Vector3d(1, 0, -3);
  var result = HitchJoint.partConnectionOffset(new Vector3d(), topLevelPartOrientation(IDENTITY), new Vector3d(1, 1, 1), connectionLocalPos, new Vector3d());
  assertEquals(new Vector3d(1, 0, -3), result);
 }

 @Test void partConnectionOffsetAppliesPartScaleBeforeOffset() {
  // A part scaled 2x on X only, installed at the vehicle's own origin with no extra rotation.
  var result = HitchJoint.partConnectionOffset(new Vector3d(), topLevelPartOrientation(IDENTITY), new Vector3d(2, 1, 1),
    new Vector3d(3, 0, 0), new Vector3d());
  assertEquals(new Vector3d(6, 0, 0), result, "connection pos scales with the part's own full accumulated scale");
 }

 @Test void partConnectionOffsetTranslatesByPartLocalOffset() {
  // An unscaled, unrotated part installed 5 units forward of the vehicle's own origin.
  var result = HitchJoint.partConnectionOffset(new Vector3d(5, 0, 0), topLevelPartOrientation(IDENTITY), new Vector3d(1, 1, 1),
    new Vector3d(0, 0, -2), new Vector3d());
  assertEquals(new Vector3d(5, 0, -2), result);
 }

 @Test void partConnectionOffsetRotatesScaledOffsetByPartOrientationBeforeTranslating() {
  // Same +90-degree yaw convention as localJointFrameConvertsAnAuthoredRotation (local +Z to
  // +X), applied to the part's own installed orientation this time, with a non-trivial scale
  // and offset too - demonstrating translated/rotated/scaled composed together, not in
  // isolation.
  var partOrientation = topLevelPartOrientation(new RotationMatrix().rotateY(90));
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
  var result = HitchJoint.partConnectionOffset(new Vector3d(), topLevelPartOrientation(IDENTITY), new Vector3d(1, 1, 1), new Vector3d(), dest);
  assertSame(dest, result);
 }

 @Test void partConnectionRotationCollapsesToPlainConversionWithIdentityPartOrientation() {
  var hookupRot = new RotationMatrix().rotateY(30);
  var result = HitchJoint.partConnectionRotation(topLevelPartOrientation(IDENTITY), hookupRot);
  assertEquals(HitchJoint.localJointFrame(hookupRot), result);
 }

 @Test void partConnectionRotationComposesPartOrientationBeforeConnectionRot() {
  // The part's own installed orientation (+90 yaw) must be applied FIRST, with the connection's
  // own authored rot (+30 yaw) composed after it - not the other way around, and not just the
  // connection's rot alone. Transforming local +Z should land where a +90-then-+30 (120 total)
  // yaw of +Z would: local +Z rotated 120 degrees about Y.
  var partOrientation = topLevelPartOrientation(new RotationMatrix().rotateY(90));
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

 // MINECRAFT-530 review kickback (comment 53994): APart.localOrientation alone drops any
 // ancestor rotation for a part nested on another part. vehicleRootLocalOrientation must divide
 // the vehicle's own world orientation out of the part's fully-recursive APart.orientation
 // instead, recovering the full ancestor chain at any nesting depth.

 @Test void vehicleRootLocalOrientationCollapsesToLocalOrientationWithNoNesting() {
  // A part installed directly on the vehicle: entityOrientation == vehicleOrientation *
  // partLocalOrientation (APart.update()'s own relation when entityOn == vehicle), so dividing
  // out vehicleOrientation must recover exactly partLocalOrientation alone. Non-commuting axes
  // (Y then X for the vehicle, Z for the part) so this cannot pass by accident the way a
  // same-axis-only case could. 1e-6 tolerance, not 1e-9: this round-trips through
  // RotationMatrix->Quaterniond conversion twice (vehicleOrientation and entityOrientation
  // separately) before recombining, and empirically accumulates ~1e-7 of floating-point error -
  // the same order of tolerance nonIdentityRotationsOnBothEndsMatchNativeComposition already
  // uses above for a comparable multi-rotation composition.
  var vehicleOrientation = new RotationMatrix().rotateY(15).rotateX(10);
  var partLocalOrientation = new RotationMatrix().rotateZ(-8);
  var entityOrientation = new RotationMatrix().set(vehicleOrientation).multiply(partLocalOrientation);
  var result = HitchJoint.vehicleRootLocalOrientation(vehicleOrientation, entityOrientation);
  double angleDiff = new Quaterniond(result).invert().mul(HitchJoint.localJointFrame(partLocalOrientation)).angle();
  assertEquals(0, angleDiff, 1e-6);
 }

 @Test void vehicleRootLocalOrientationRecoversTheFullAncestorChainWhenNested() {
  // A part nested two levels deep: vehicle -> parentPart -> childPart, each contributing its own
  // local rotation (APart.orientation = entityOn.orientation * localOrientation, recursively, so
  // childPart.orientation == vehicleOrientation * parentPart's own local rot * childPart's own
  // local rot). Deliberately non-commuting axes throughout (X, then Y, then Z) so this cannot
  // pass by accident the way a same-axis-only chain could (a same-axis chain would collapse to a
  // single angle sum regardless of composition order). The CHILD's raw APart.localOrientation
  // alone is only its own rotation - exactly the quantity review 53994 found missing the
  // parent's own contribution. vehicleRootLocalOrientation, given the child's fully-recursive
  // orientation, must recover the FULL ancestor chain (parentOwnLocalRot*childOwnLocalRot, in
  // that order), not just the child's own piece.
  var vehicleOrientation = new RotationMatrix().rotateX(12);
  var parentOwnLocalRot = new RotationMatrix().rotateY(40);
  var childOwnLocalRot = new RotationMatrix().rotateZ(25);
  var childFullOrientation = new RotationMatrix().set(vehicleOrientation).multiply(parentOwnLocalRot).multiply(childOwnLocalRot);
  var expectedFullChain = new RotationMatrix().set(parentOwnLocalRot).multiply(childOwnLocalRot);

  var result = HitchJoint.vehicleRootLocalOrientation(vehicleOrientation, childFullOrientation);
  double angleDiffFromFullChain = new Quaterniond(result).invert().mul(HitchJoint.localJointFrame(expectedFullChain)).angle();
  assertEquals(0, angleDiffFromFullChain, 1e-6);

  // And it must differ from using the child's own raw localOrientation alone - the bug this test
  // exists to catch.
  double angleDiffFromChildOwnRotAlone = new Quaterniond(result).invert().mul(HitchJoint.localJointFrame(childOwnLocalRot)).angle();
  assertTrue(angleDiffFromChildOwnRotAlone > 1e-6);
 }

 @Test void vehicleRootLocalOrientationIsIdentityWhenEntityIsTheVehicleItself() {
  // The vehicle-root connection case: entityOrientation IS vehicleOrientation (no part at all),
  // so dividing it out must leave identity.
  var vehicleOrientation = new RotationMatrix().rotateY(37).rotateZ(5);
  var result = HitchJoint.vehicleRootLocalOrientation(vehicleOrientation, vehicleOrientation);
  assertEquals(0, new Quaterniond(result).angle(), 1e-9);
 }
}
