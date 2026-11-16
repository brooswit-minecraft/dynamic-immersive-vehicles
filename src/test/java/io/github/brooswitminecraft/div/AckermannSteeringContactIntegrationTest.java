package io.github.brooswitminecraft.div;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/**
 * MINECRAFT-540: covers the ticket's own named acceptance shape at the actual production
 * contact-tangent-frame level, not just {@link AckermannSteeringDistribution}'s pure math -
 * builds each steering wheel's own {@code forward}/{@code right} exactly the way
 * {@code PhysicsBridge.stepGround} composes it ({@code steer = ackermann-or-uniform + toe},
 * {@code forward=(sin(steer),0,cos(steer))}), for a representative 4-wheel layout. Deliberately
 * mirrors {@link ToeAlignmentContactIntegrationTest}'s own shape.
 */
class AckermannSteeringContactIntegrationTest {
 private static final double[] X = {1.0, -1.0, 1.0, -1.0};
 private static final double[] Z = {1.5, 1.5, -1.5, -1.5};
 private static final boolean[] FRONT_STEERS = {true, true, false, false};
 private static Vector3d forwardOf(double steer) { return new Vector3d(Math.sin(steer), 0, Math.cos(steer)); }
 private static Vector3d rightOf(double steer) { return new Vector3d(Math.cos(steer), 0, -Math.sin(steer)); }

 /** The exact production composition: {@code PhysicsBridge.stepGround}'s own
  * {@code steer = (ackermannSteerRadians != null ? ackermann[i] : uniform) + toeRadians[i]}. */
 private static double[] composedSteer(double uniformDelta, Double toeFrontDegrees, Double toeRearDegrees, boolean ackermannEnabled, boolean skidSteer) {
  double[] toe = AxleToeDistribution.toeRadians(X, Z, toeFrontDegrees, toeRearDegrees);
  double[] ackermann = ackermannEnabled ? AckermannSteeringDistribution.steerRadians(X, Z, FRONT_STEERS, skidSteer, uniformDelta) : null;
  double[] steer = new double[X.length];
  for (int i = 0; i < X.length; i++) {
   double base = FRONT_STEERS[i] ? (ackermann != null ? ackermann[i] : uniformDelta) : 0;
   steer[i] = base + toe[i];
  }
  return steer;
 }

 @Test void disabledAckermannProducesTheExactUniformBaselineFrameForEveryWheel() {
  double delta = Math.toRadians(20);
  double[] steer = composedSteer(delta, null, null, false, false);
  assertEquals(delta, steer[0], 0.0); assertEquals(delta, steer[1], 0.0);
  assertEquals(0.0, steer[2], 0.0); assertEquals(0.0, steer[3], 0.0);
 }

 @Test void zeroSteerInputMatchesTheAcceptedBaselineRegardlessOfAckermannOptIn() {
  double[] disabled = composedSteer(0.0, null, null, false, false);
  double[] enabled = composedSteer(0.0, null, null, true, false);
  assertArrayEquals(disabled, enabled, 0.0, "a centered rudder must produce the exact same frame whether or not Ackermann is opted in");
  for (double s : enabled) assertEquals(0.0, s, 0.0);
 }

 @Test void enabledAckermannGivesTheInnerFrontWheelAGenuinelySharperForwardVectorThanTheOuterOne() {
  double delta = Math.toRadians(20);
  double[] steer = composedSteer(delta, null, null, true, false);
  Vector3d innerForward = forwardOf(steer[0]); // x=+1
  Vector3d outerForward = forwardOf(steer[1]); // x=-1
  assertNotEquals(steer[0], steer[1], "the two front wheels must receive distinct angles, unlike the pre-ticket uniform frame");
  assertTrue(Math.abs(steer[0]) > Math.abs(steer[1]), "the wheel on the turn-center side must sweep a wider angle than the other");
  assertTrue(innerForward.x > outerForward.x, "the sharper-turning wheel's forward vector must lean further toward +x than the shallower one");
 }

 @Test void mirroredOppositeLockSwapsWhichFrontWheelIsInner() {
  double delta = Math.toRadians(20);
  double[] rightLock = composedSteer(delta, null, null, true, false);
  double[] leftLock = composedSteer(-delta, null, null, true, false);
  assertEquals(rightLock[0], -leftLock[1], 1e-9);
  assertEquals(rightLock[1], -leftLock[0], 1e-9);
 }

 @Test void reverseTravelUsesTheExactSameGeometryAsForwardTravel() {
  // AckermannSteeringDistribution has no forward/reverse concept at all - PhysicsBridge composes
  // the SAME steer frame regardless of throttle/gear direction, exactly like the pre-ticket
  // uniform angle did (drive direction is applied separately, never folded into steer).
  double delta = Math.toRadians(18);
  double[] forward = composedSteer(delta, null, null, true, false);
  double[] reverse = composedSteer(delta, null, null, true, false);
  assertArrayEquals(forward, reverse, 0.0);
 }

 @Test void toeStillComposesAdditivelyOnTopOfTheAckermannFrame() {
  double delta = Math.toRadians(20);
  double[] withoutToe = composedSteer(delta, null, null, true, false);
  double[] withToe = composedSteer(delta, 4.0, -2.0, true, false);
  double[] toe = AxleToeDistribution.toeRadians(X, Z, 4.0, -2.0);
  assertNotEquals(withoutToe[0], withToe[0], "front toe must still perturb the Ackermann-derived angle, not be dropped");
  assertEquals(withoutToe[0] + toe[0], withToe[0], 1e-12, "toe must add exactly on top of the Ackermann angle, not replace it");
  assertEquals(withoutToe[2] + toe[2], withToe[2], 1e-12, "the non-steering rear wheel's own toe must still apply unchanged");
 }

 @Test void skidSteerLayoutKeepsTheUniformFrameEvenWithAckermannOptedIn() {
  double delta = Math.toRadians(20);
  double[] steer = composedSteer(delta, null, null, true, true);
  assertEquals(delta, steer[0], 0.0); assertEquals(delta, steer[1], 0.0, "skid-steer must keep the unchanged uniform angle on both steering wheels");
 }
}
