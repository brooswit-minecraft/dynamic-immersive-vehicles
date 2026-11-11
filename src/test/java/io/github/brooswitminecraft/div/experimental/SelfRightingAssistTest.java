package io.github.brooswitminecraft.div.experimental;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Coverage for ticket comment 53182's original test list plus comment 53184's corrections
 * (pure pitch/yaw exclusion, pitched+banked pose, upside-down gating, zero-crossing continuity -
 * the earlier cross-product-based version failed all four of these). No-wobble retention needs
 * the real integration once this is wired into Body - flagged as not yet covered here. */
class SelfRightingAssistTest {
 private static final Vector3d WORLD_UP=new Vector3d(0,1,0);
 private static final SelfRightingAssist.Config TYPICAL=new SelfRightingAssist.Config(500, 100, 2000, 15, 60);
 private record Basis(Vector3d forward, Vector3d up, Vector3d right) {}
 /** Pure roll about world Z (forward stays world Z) by rollDegrees, then yawed by yawDegrees
  * about world Y, so the whole basis still has forward/up/right well-defined and orthonormal. */
 private static Basis basisRollThenYaw(double rollDegrees, double pitchDegrees, double yawDegrees) {
  Quaterniond q=new Quaterniond().rotateY(Math.toRadians(yawDegrees)).rotateX(Math.toRadians(pitchDegrees)).rotateZ(Math.toRadians(-rollDegrees));
  return new Basis(q.transform(new Vector3d(0,0,1)), q.transform(new Vector3d(0,1,0)), q.transform(new Vector3d(1,0,0)));
 }
 private static Vector3d torqueAt(SelfRightingAssist.Config config, Basis b, Vector3d angularVelocity, boolean occupied, boolean grounded) {
  return SelfRightingAssist.torque(config, b.forward(), b.up(), b.right(), angularVelocity, occupied, grounded);
 }

 @Test void offConfigIsAlwaysExactlyZeroRegardlessOfState() {
  var b=basisRollThenYaw(30,0,0);
  var t=torqueAt(SelfRightingAssist.OFF, b, new Vector3d(1,1,1), true, true);
  assertEquals(0, t.length(), 1e-12);
 }

 @Test void uprightAndMotionlessProducesZeroTorque() {
  var b=basisRollThenYaw(0,0,0);
  var t=torqueAt(TYPICAL, b, new Vector3d(0,0,0), true, true);
  assertEquals(0, t.length(), 1e-9);
 }

 @Test void restoringTorqueSignPointsTowardUprightNotAwayFromIt() {
  // d(rollRadians)/dt = angularVelocity.dot(forward) (verified algebraically in the class doc),
  // so a torque along +forward increases rollRadians, along -forward decreases it. A restoring
  // torque must therefore have the OPPOSITE sign from the current rollRadians, whatever that
  // sign convention happens to be for a given input - not a fixed "positive input -> positive
  // torque" assumption, which depends on how rollDegrees maps to rollRadians internally.
  var b=basisRollThenYaw(20,0,0);
  var t=torqueAt(TYPICAL, b, new Vector3d(0,0,0), true, true);
  double rollRadians=Math.atan2(b.right().y, b.up().y);
  double torqueAlongForward=t.dot(b.forward().normalize());
  assertTrue(Math.signum(torqueAlongForward) == -Math.signum(rollRadians),
    "restoring torque must have the opposite sign from the current roll angle - rollRadians="+rollRadians+" torqueAlongForward="+torqueAlongForward);
 }

 @Test void torqueMagnitudeNeverExceedsMaxTorque() {
  var b=basisRollThenYaw(40,0,0);
  var t=torqueAt(TYPICAL, b, new Vector3d(0,0,1000), true, true);
  assertTrue(t.length() <= TYPICAL.maxTorque()+1e-9);
 }

 @Test void pureYawProducesNoTorqueAtAll() {
  for (double yaw : new double[]{10,45,170,-90}) {
   var b=basisRollThenYaw(0,0,yaw);
   var t=torqueAt(TYPICAL, b, new Vector3d(0,0,0), true, true);
   assertEquals(0, t.length(), 1e-9, "pure yaw (no roll, no pitch) must produce zero assist torque, yaw="+yaw);
  }
 }

 @Test void purePitchAtAnyMagnitudeWithinSafeRangeProducesNoTorque() {
  // Comment 53184's core complaint: the old cross-product axis also "corrected" pure pitch -
  // this must NOT happen. Checked up to 80deg pitch (below the 90deg point where this 2-axis
  // roll decomposition's own known limitation - cos(pitch) changing sign - would otherwise read
  // as an artificial inversion; that edge case is a separate, disclosed limitation, not silently
  // covered here).
  for (double pitch : new double[]{10,45,80,-60}) {
   var b=basisRollThenYaw(0,pitch,0);
   var t=torqueAt(TYPICAL, b, new Vector3d(0,0,0), true, true);
   assertEquals(0, t.length(), 1e-9, "pure pitch (a wheelie-type tilt) must produce zero torque under this ROLL assist, pitch="+pitch);
  }
 }

 @Test void rollIsDetectedCorrectlyEvenWhenCombinedWithPitchAndYaw() {
  var pureRoll=torqueAt(TYPICAL, basisRollThenYaw(25,0,0), new Vector3d(0,0,0), true, true);
  var rollPlusPitchYaw=torqueAt(TYPICAL, basisRollThenYaw(25,30,50), new Vector3d(0,0,0), true, true);
  assertTrue(rollPlusPitchYaw.length() > 0, "the same roll combined with pitch+yaw must still produce a nonzero restoring torque - pitch/yaw must not mask real roll");
  // Not asserting exact equality with pure-roll (pitch changes which world axis is "forward",
  // changing the torque's own world-frame direction) - just that roll is still detected.
 }

 @Test void dampingIsContinuousThroughExactlyZeroBankUnlikeTheOldDegenerateAxisVersion() {
  // Comment 53184: at exactly upright, nonzero roll rate must still be damped - the old version
  // dropped all damping here because its axis (bodyUp x worldUp) is the zero vector at zero bank.
  var upright=basisRollThenYaw(0,0,0);
  var t=torqueAt(TYPICAL, upright, new Vector3d(0,0,5), true, true); // angular velocity along bodyForward=(0,0,1)
  assertTrue(t.length() > 0, "nonzero roll rate at exactly zero bank must still produce a nonzero (damping) torque");
  assertTrue(t.z < 0, "damping must oppose a positive roll rate, i.e. point along -forward here");
 }

 @Test void dampingOpposesRollRateAtATiltedBank() {
  var b=basisRollThenYaw(10,0,0);
  var withPositiveRollRate=torqueAt(TYPICAL, b, new Vector3d(0,0,2), true, true);
  var withZeroRollRate=torqueAt(TYPICAL, b, new Vector3d(0,0,0), true, true);
  assertTrue(withPositiveRollRate.z < withZeroRollRate.z, "damping must reduce the torque when the vehicle is already rolling toward upright on its own, not add to it");
 }

 @Test void gatingZeroesOutputWhenUnoccupiedOrAirborne() {
  var b=basisRollThenYaw(20,0,0);
  assertEquals(0, torqueAt(TYPICAL, b, new Vector3d(0,0,0), false, true).length(), 1e-12);
  assertEquals(0, torqueAt(TYPICAL, b, new Vector3d(0,0,0), true, false).length(), 1e-12);
 }

 @Test void gatingFadesToZeroAtConfiguredFadeEndAndStaysZeroBeyondIt() {
  var atFadeEnd=torqueAt(TYPICAL, basisRollThenYaw(TYPICAL.fadeEndDegrees(),0,0), new Vector3d(0,0,0), true, true);
  var pastFadeEnd=torqueAt(TYPICAL, basisRollThenYaw(TYPICAL.fadeEndDegrees()+10,0,0), new Vector3d(0,0,0), true, true);
  assertEquals(0, atFadeEnd.length(), 1e-9);
  assertEquals(0, pastFadeEnd.length(), 1e-12);
 }

 @Test void hardSafetyCapZeroesAssistNearUpsideDownEvenWithAPermissiveFadeEndConfig() {
  // A deliberately permissive config using the max allowed fadeEndDegrees - the HARD cap must
  // still zero it out well before actual inversion, independent of this config choice.
  var permissive=new SelfRightingAssist.Config(500,100,2000,80, SelfRightingAssist.HARD_SAFETY_CAP_DEGREES);
  var nearUpsideDown=torqueAt(permissive, basisRollThenYaw(89.9,0,0), new Vector3d(0,0,0), true, true);
  var atCap=torqueAt(permissive, basisRollThenYaw(SelfRightingAssist.HARD_SAFETY_CAP_DEGREES,0,0), new Vector3d(0,0,0), true, true);
  assertEquals(0, atCap.length(), 1e-9, "at the hard safety cap angle, assist must be zero regardless of config");
  assertTrue(nearUpsideDown.length() >= 0); // sanity: does not throw/NaN this close to the cap
 }

 @Test void leftAndRightTiltsProduceMirroredNotBiasedTorque() {
  var right=torqueAt(TYPICAL, basisRollThenYaw(25,0,0), new Vector3d(0,0,0), true, true);
  var left=torqueAt(TYPICAL, basisRollThenYaw(-25,0,0), new Vector3d(0,0,0), true, true);
  assertEquals(right.length(), left.length(), 1e-9);
  assertEquals(-right.z, left.z, 1e-9);
 }

 // Review comment 53186: atan2(bodyRight.y,bodyUp.y) IS degenerate when bodyForward is vertical
 // (an extreme nose-up/down attitude) - both arguments approach zero together there.

 @Test void nearVerticalForwardWithZeroRollStillProducesFiniteZeroTorque() {
  for (double pitch : new double[]{89.99, 89.999999, -89.99}) {
   var b=basisRollThenYaw(0, pitch, 0);
   var t=torqueAt(TYPICAL, b, new Vector3d(0.1,0.1,0.1), true, true);
   assertTrue(Double.isFinite(t.x) && Double.isFinite(t.y) && Double.isFinite(t.z), "pitch="+pitch+" must never produce a non-finite torque near the gimbal attitude - got "+t);
  }
 }

 @Test void torqueFadesSmoothlyToZeroApproachingVerticalForwardEvenWithNonzeroRoll() {
  // A small roll combined with pitch approaching 90deg - the gimbal fade must win and bring the
  // torque down to (near) zero as pitch approaches vertical, not report an arbitrarily large or
  // sign-flipping value from the increasingly noise-sensitive angle.
  var moderatePitch=torqueAt(TYPICAL, basisRollThenYaw(10, 70, 0), new Vector3d(0,0,0), true, true);
  var nearVerticalPitch=torqueAt(TYPICAL, basisRollThenYaw(10, 89.9, 0), new Vector3d(0,0,0), true, true);
  assertTrue(nearVerticalPitch.length() < moderatePitch.length(), "torque magnitude must shrink as the vehicle's forward axis approaches vertical, not stay constant or grow");
 }

 @Test void noArbitraryRollFlipCrossingThroughNearVerticalPitchWithASmallRollPerturbation() {
  // Two nearly-identical near-vertical attitudes differing only by a tiny roll perturbation must
  // NOT produce wildly different (e.g. sign-flipped, large-magnitude) torques - both should be
  // small and well-behaved because the gimbal fade dominates near this attitude either way.
  var a=torqueAt(TYPICAL, basisRollThenYaw(1, 89.95, 0), new Vector3d(0,0,0), true, true);
  var b=torqueAt(TYPICAL, basisRollThenYaw(-1, 89.95, 0), new Vector3d(0,0,0), true, true);
  assertTrue(a.length() < 50 && b.length() < 50, "torque must stay small this close to vertical forward regardless of a tiny roll perturbation - got a="+a+" b="+b);
 }

 @Test void exactlyVerticalForwardProducesExactlyZeroNotNaN() {
  var b=basisRollThenYaw(0, 90, 0);
  var t=torqueAt(TYPICAL, b, new Vector3d(1,1,1), true, true);
  assertEquals(0, t.length(), 1e-9, "exactly vertical forward (bodyRight.y=bodyUp.y=0, true atan2(0,0) degeneracy) must resolve to exactly zero, not NaN or an arbitrary angle");
 }

 @Test void configRejectsInvalidRangesIncludingBeyondTheHardSafetyCap() {
  assertThrows(IllegalArgumentException.class, () -> new SelfRightingAssist.Config(-1, 0, 100, 10, 60));
  assertThrows(IllegalArgumentException.class, () -> new SelfRightingAssist.Config(100, -1, 100, 10, 60));
  assertThrows(IllegalArgumentException.class, () -> new SelfRightingAssist.Config(100, 0, -1, 10, 60));
  assertThrows(IllegalArgumentException.class, () -> new SelfRightingAssist.Config(100, 0, 100, 60, 10));
  assertThrows(IllegalArgumentException.class, () -> new SelfRightingAssist.Config(100, 0, 100, 10, 180),
    "fadeEndDegrees beyond the hard safety cap must be rejected, not silently accepted as it was before comment 53184's fix");
 }
}
