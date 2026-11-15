package io.github.brooswitminecraft.div;
import io.github.brooswitminecraft.div.experimental.MultiContactFrictionSolver;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/**
 * MINECRAFT-533: covers the ticket's own named acceptance shape at the actual production contact-
 * tangent-frame level, not just {@link AxleToeDistribution}'s pure math - builds each wheel's own
 * {@code forward}/{@code right} exactly the way {@code PhysicsBridge.stepGround} does
 * ({@code forward=(sin(steer),0,cos(steer))}, {@code right=(cos(steer),0,-sin(steer))} for the
 * identity-orientation/{@code up=(0,1,0)} case this test uses), with {@code steer} set to
 * {@link AxleToeDistribution}'s own per-wheel offset, then drives the real
 * {@link MultiContactFrictionSolver#solveFriction} against a representative 4-wheel layout -
 * straight/turning/reverse travel, mirrored symmetry (no unintended net lateral force at a
 * symmetric state), and a meaningful aligned-force difference from toe actually being configured.
 * Deliberately mirrors {@link ServiceDriveBiasTest}'s own solver-level shape.
 */
class ToeAlignmentContactIntegrationTest {
 private static final double MASS = 1500, DT = 0.05, FRICTION = 1.0, NORMAL = 3750, TOTAL_DRIVE_FORCE = 8000;
 private static final Vector3d INERTIA = new Vector3d(600, 900, 1400);
 private static final Quaterniond IDENTITY = new Quaterniond();
 // Same representative 4-wheel layout as ServiceDriveBiasTest: front axle at z=+1.5, rear axle
 // at z=-1.5.
 private static final double[] X = {1.0, -1.0, 1.0, -1.0};
 private static final double[] Z = {1.5, 1.5, -1.5, -1.5};
 private static final Vector3d[] POINTS = {
   new Vector3d(1.0, -0.4, 1.5), new Vector3d(-1.0, -0.4, 1.5),
   new Vector3d(1.0, -0.4, -1.5), new Vector3d(-1.0, -0.4, -1.5),
 };
 /** The exact per-wheel forward/right {@code PhysicsBridge.stepGround} builds from a {@code
  * steer} angle, for the identity-orientation/{@code up=(0,1,0)} case this test uses. */
 private static Vector3d forwardOf(double steer) { return new Vector3d(Math.sin(steer), 0, Math.cos(steer)); }
 private static Vector3d rightOf(double steer) { return new Vector3d(Math.cos(steer), 0, -Math.sin(steer)); }
 private static MultiContactFrictionSolver.FrictionContact[] contactsFor(Double toeFrontDegrees, Double toeRearDegrees) {
  double[] toe = AxleToeDistribution.toeRadians(X, Z, toeFrontDegrees, toeRearDegrees);
  var contacts = new MultiContactFrictionSolver.FrictionContact[4];
  for (int i = 0; i < 4; i++) {
   contacts[i] = new MultiContactFrictionSolver.FrictionContact(POINTS[i], forwardOf(toe[i]), rightOf(toe[i]), NORMAL, 1.0, 1.0, 1.0, 0.0, 1.0);
  }
  return contacts;
 }
 private static Vector3d driveForOneTick(Vector3d initialVelocity, Double toeFrontDegrees, Double toeRearDegrees, int gearSign) {
  Vector3d velocity = new Vector3d(initialVelocity);
  Vector3d angular = new Vector3d(0, 0, 0);
  var contacts = contactsFor(toeFrontDegrees, toeRearDegrees);
  double driveForcePerWheel = gearSign * TOTAL_DRIVE_FORCE / 4;
  MultiContactFrictionSolver.solveFriction(contacts, MASS, INERTIA, IDENTITY, IDENTITY, velocity, angular, 0, driveForcePerWheel, FRICTION, 1.0, DT, 64);
  return velocity;
 }
 @Test void mirroredToeInFromRestProducesNoUnintendedNetLateralForceGoingStraight() {
  Vector3d finalVelocity = driveForOneTick(new Vector3d(0, 0, 0), 6.0, -2.0, 1);
  assertEquals(0.0, finalVelocity.x, 1e-9, "a symmetric (mirrored) toe configuration starting from rest must produce no net world-frame lateral (x) velocity");
  assertTrue(finalVelocity.z > 0.0, "the vehicle must still accelerate forward despite the configured toe, got " + finalVelocity.z);
 }
 @Test void mirroredToeInFromRestProducesNoUnintendedNetLateralForceInReverse() {
  Vector3d finalVelocity = driveForOneTick(new Vector3d(0, 0, 0), 4.0, 4.0, -1);
  assertEquals(0.0, finalVelocity.x, 1e-9, "a symmetric toe configuration must still cancel net lateral force in reverse");
  assertTrue(finalVelocity.z < 0.0, "reverse drive must still accelerate the vehicle backward despite the configured toe, got " + finalVelocity.z);
 }
 @Test void zeroToeIsAByteIdenticalNoOpToTheUnconfiguredPreTicketBehavior() {
  Vector3d withNullToe = driveForOneTick(new Vector3d(0, 0, 0), null, null, 1);
  Vector3d withZeroToe = driveForOneTick(new Vector3d(0, 0, 0), 0.0, 0.0, 1);
  assertEquals(withNullToe.x, withZeroToe.x, 0.0);
  assertEquals(withNullToe.z, withZeroToe.z, 0.0);
 }
 @Test void configuredToeMakesAMeaningfulDifferenceToTheLateralForceWhenTheVehicleIsAlreadySliding() {
  // Simulate "turning"/sliding: a nonzero initial lateral (world x) velocity, as a car already
  // mid-slide would have. A configured front toe must measurably change how much of that slide
  // the front axle's own lateral grip resists this tick, compared to no toe at all - toe is not
  // a no-op once there is real lateral slip for it to act against.
  Vector3d slidingStart = new Vector3d(1.5, 0, 3.0);
  Vector3d noToeResult = driveForOneTick(slidingStart, null, null, 1);
  Vector3d toedResult = driveForOneTick(slidingStart, 8.0, 0.0, 1);
  assertNotEquals(noToeResult.x, toedResult.x, 1e-6, "a configured front toe must produce a measurably different lateral outcome than no toe at all while the vehicle is sliding");
 }
 @Test void turningWithRudderInputStillComposesAdditivelyWithASteerableWheelsToe() {
  // The ticket's own "preserve IV steering" requirement: toe must compose ADDITIVELY with a
  // steerable wheel's own rudder-driven steer angle, never replacing it - modeled here directly
  // as steer = rudderAngle + toeOffset, the same composition PhysicsBridge.stepGround performs.
  double toeOffset = AxleToeDistribution.toeRadians(X, Z, 5.0, null)[0]; // front-right wheel's own toe
  double rudderAngle = Math.toRadians(10.0); // some nonzero steering input
  var withToe = new MultiContactFrictionSolver.FrictionContact(POINTS[0], forwardOf(rudderAngle + toeOffset), rightOf(rudderAngle + toeOffset), NORMAL, 1.0);
  var withoutToe = new MultiContactFrictionSolver.FrictionContact(POINTS[0], forwardOf(rudderAngle), rightOf(rudderAngle), NORMAL, 1.0);
  assertNotEquals(withToe.forward(), withoutToe.forward(), "toe must shift the steerable wheel's own contact frame away from the pure-rudder angle, not get discarded");
 }
}
