package io.github.brooswitminecraft.div.experimental;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Coverage for the exact test list ticket comment 53182 specified: zero/off equivalence, torque
 * sign/bounds, damping/passivity without input, high-bank/airborne/unoccupied gating, left/right
 * symmetry. No-wobble retention needs the real integration once this is wired into Body -
 * flagged as not yet covered here, not silently skipped. */
class SelfRightingAssistTest {
 private static final Vector3d WORLD_UP=new Vector3d(0,1,0);
 private static final SelfRightingAssist.Config TYPICAL=new SelfRightingAssist.Config(500, 100, 2000, 15, 60);

 private static Vector3d tiltedBodyUp(double degreesTowardX) {
  // Tilt purely about the world Z axis (a pure roll, body forward stays along world Z) so the
  // restoring axis (bodyUp x worldUp) comes out along world Z too - easy to reason about.
  return new Quaterniond().rotateZ(-Math.toRadians(degreesTowardX)).transform(new Vector3d(0,1,0));
 }

 @Test void offConfigIsAlwaysExactlyZeroRegardlessOfState() {
  var off=SelfRightingAssist.OFF;
  var t1=SelfRightingAssist.torque(off, tiltedBodyUp(30), WORLD_UP, new Vector3d(1,1,1), true, true);
  var t2=SelfRightingAssist.torque(off, tiltedBodyUp(5), WORLD_UP, new Vector3d(0,0,0), true, true);
  assertEquals(0, t1.length(), 1e-12);
  assertEquals(0, t2.length(), 1e-12);
 }

 @Test void zeroBankAndZeroAngularVelocityProducesZeroTorque() {
  var t=SelfRightingAssist.torque(TYPICAL, WORLD_UP, WORLD_UP, new Vector3d(0,0,0), true, true);
  assertEquals(0, t.length(), 1e-9, "a perfectly upright, motionless vehicle must get zero restoring torque - nothing to restore");
 }

 @Test void restoringTorqueSignPointsTowardUprightNotAwayFromIt() {
  Vector3d bodyUp=tiltedBodyUp(20); // tilted toward +X
  var t=SelfRightingAssist.torque(TYPICAL, bodyUp, WORLD_UP, new Vector3d(0,0,0), true, true);
  // A torque about +Z rotates +X toward +Y (right-hand rule) - i.e. restoring this +X-ward tilt
  // back toward upright needs a POSITIVE Z-axis torque component here.
  assertTrue(t.z > 0, "restoring torque must point toward upright, not away from it - got "+t);
  assertEquals(0, t.x, 1e-9); assertEquals(0, t.y, 1e-9); // pure roll tilt must produce a pure Z-axis torque, no cross-axis leakage
 }

 @Test void torqueMagnitudeNeverExceedsMaxTorque() {
  // A large bank still within the fade window, plus a large destabilizing angular velocity, to
  // try to push restoring+damping combined past maxTorque.
  Vector3d bodyUp=tiltedBodyUp(40);
  var t=SelfRightingAssist.torque(TYPICAL, bodyUp, WORLD_UP, new Vector3d(0,0,1000), true, true);
  assertTrue(t.length() <= TYPICAL.maxTorque()+1e-9, "combined torque must never exceed the configured maxTorque - got |t|="+t.length());
 }

 @Test void atExactlyZeroBankTheUndefinedAxisMeansNoDampingEither() {
  // Zero bank (nothing to restore) - the restoring AXIS itself (bodyUp x worldUp) is the zero
  // vector here, so there is no direction to project angular velocity onto for damping either;
  // output is correctly zero, not an arbitrary direction picked from a degenerate cross product.
  var t=SelfRightingAssist.torque(TYPICAL, WORLD_UP, WORLD_UP, new Vector3d(0,0,5), true, true);
  assertEquals(0, t.length(), 1e-9, "a perfectly upright vehicle has no defined roll axis to damp against");
 }

 @Test void dampingOpposesRollRateAtATiltedBank() {
  Vector3d bodyUp=tiltedBodyUp(10);
  // Roll rate along +Z (same direction the earlier sign test showed restoring torque points) -
  // damping must SUBTRACT from the restoring torque here, never add to it (passivity: damping
  // never drives the system further from rest).
  var withPositiveRollRate=SelfRightingAssist.torque(TYPICAL, bodyUp, WORLD_UP, new Vector3d(0,0,2), true, true);
  var withZeroRollRate=SelfRightingAssist.torque(TYPICAL, bodyUp, WORLD_UP, new Vector3d(0,0,0), true, true);
  assertTrue(withPositiveRollRate.z < withZeroRollRate.z, "damping must reduce the torque when the vehicle is already rolling toward upright on its own, not add to it");
 }

 @Test void gatingZeroesOutputWhenUnoccupiedOrAirborne() {
  Vector3d bodyUp=tiltedBodyUp(20);
  var unoccupied=SelfRightingAssist.torque(TYPICAL, bodyUp, WORLD_UP, new Vector3d(0,0,0), false, true);
  var airborne=SelfRightingAssist.torque(TYPICAL, bodyUp, WORLD_UP, new Vector3d(0,0,0), true, false);
  assertEquals(0, unoccupied.length(), 1e-12, "no rider/control occupancy must mean zero assist, even while banked");
  assertEquals(0, airborne.length(), 1e-12, "no grounded support must mean zero assist - never an automatic mid-air recovery");
 }

 @Test void gatingFadesToExactlyZeroAtAndBeyondTheConfiguredMaxBankAngle() {
  var atFadeEnd=SelfRightingAssist.torque(TYPICAL, tiltedBodyUp(TYPICAL.fadeEndDegrees()), WORLD_UP, new Vector3d(0,0,0), true, true);
  var pastFadeEnd=SelfRightingAssist.torque(TYPICAL, tiltedBodyUp(TYPICAL.fadeEndDegrees()+20), WORLD_UP, new Vector3d(0,0,0), true, true);
  var nearInverted=SelfRightingAssist.torque(TYPICAL, tiltedBodyUp(170), WORLD_UP, new Vector3d(0,0,0), true, true);
  assertEquals(0, atFadeEnd.length(), 1e-9, "at the configured fadeEndDegrees, assist must already be exactly zero");
  assertEquals(0, pastFadeEnd.length(), 1e-12, "well past the fade window there must be no assist");
  assertEquals(0, nearInverted.length(), 1e-12, "a near-inverted vehicle must get no assist - this is corrective help for a basically-upright vehicle, not a flip-recovery system");
 }

 @Test void leftAndRightTiltsProduceMirroredNotBiasedTorque() {
  var right=SelfRightingAssist.torque(TYPICAL, tiltedBodyUp(25), WORLD_UP, new Vector3d(0,0,0), true, true);
  var left=SelfRightingAssist.torque(TYPICAL, tiltedBodyUp(-25), WORLD_UP, new Vector3d(0,0,0), true, true);
  assertEquals(right.length(), left.length(), 1e-9, "tilting the same amount either direction must produce the same-magnitude restoring torque");
  assertEquals(-right.z, left.z, 1e-9, "the two directions' torques must be exact mirror images (opposite sign, same magnitude), not biased toward one side");
 }

 @Test void fadeLinearlyReducesStrengthBetweenStartAndEndAngles() {
  double mid=(TYPICAL.fadeStartDegrees()+TYPICAL.fadeEndDegrees())/2;
  var atStart=SelfRightingAssist.torque(TYPICAL, tiltedBodyUp(TYPICAL.fadeStartDegrees()), WORLD_UP, new Vector3d(0,0,0), true, true);
  var atMid=SelfRightingAssist.torque(TYPICAL, tiltedBodyUp(mid), WORLD_UP, new Vector3d(0,0,0), true, true);
  // sin(bank) itself grows with angle even as the fade factor shrinks - so this only checks the
  // fade is actually doing SOMETHING (midpoint's fade-adjusted torque is below what a naive
  // sin(bank)-only scaling at full strength would give), not a strict monotonic magnitude claim.
  double impliedFullStrengthAtMid=TYPICAL.strength()*Math.sin(Math.toRadians(mid));
  assertTrue(atMid.length() < impliedFullStrengthAtMid, "fade must measurably reduce torque below what full strength alone would give at this angle");
 }

 @Test void configRejectsInvalidRanges() {
  assertThrows(IllegalArgumentException.class, () -> new SelfRightingAssist.Config(-1, 0, 100, 10, 60));
  assertThrows(IllegalArgumentException.class, () -> new SelfRightingAssist.Config(100, -1, 100, 10, 60));
  assertThrows(IllegalArgumentException.class, () -> new SelfRightingAssist.Config(100, 0, -1, 10, 60));
  assertThrows(IllegalArgumentException.class, () -> new SelfRightingAssist.Config(100, 0, 100, 60, 10), "fadeEndDegrees must exceed fadeStartDegrees");
  assertThrows(IllegalArgumentException.class, () -> new SelfRightingAssist.Config(100, 0, 100, 10, 200), "fadeEndDegrees must be <=180");
 }
}
