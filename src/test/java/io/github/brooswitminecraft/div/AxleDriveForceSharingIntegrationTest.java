package io.github.brooswitminecraft.div;
import io.github.brooswitminecraft.div.experimental.MultiContactFrictionSolver;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/**
 * MINECRAFT-532: real-production-path integration against the actual {@link
 * MultiContactFrictionSolver#solveFriction}, combining {@link DriveBiasDistribution#shareRatios}
 * (the existing axle-budget split) with {@link AxleDriveForceSharing#shareRatios} (this ticket's
 * own contact-aware within-axle redistribution) exactly the way {@code PhysicsBridge.stepGround}
 * composes them, on the same representative 4-wheel layout {@link ServiceDriveBiasTest} uses.
 * Covers: one wheel airborne actually recovers lost acceleration versus the pre-ticket
 * (disabled) behavior, reverse travel is unaffected by the composition, and conservation holds
 * at the real driveForcePerWheel*skidSteerMultiplier*driveShareRatio call site.
 */
class AxleDriveForceSharingIntegrationTest {
 private static final double MASS = 1500, DT = 0.05, FRICTION = 1.0, NORMAL = 3750, TOTAL_DRIVE_FORCE = 8000;
 private static final Vector3d INERTIA = new Vector3d(600, 900, 1400);
 private static final Quaterniond IDENTITY = new Quaterniond();
 private static final Vector3d FORWARD = new Vector3d(0, 0, 1), RIGHT = new Vector3d(1, 0, 0);
 private static final Vector3d[] POINTS = {
   new Vector3d(1.0, -0.4, 1.5), new Vector3d(-1.0, -0.4, 1.5),
   new Vector3d(1.0, -0.4, -1.5), new Vector3d(-1.0, -0.4, -1.5),
 };
 private static final double[] Z = {1.5, 1.5, -1.5, -1.5};
 private static double driveForOneTickWithOneWheelAirborne(boolean sharingEnabled, int gearSign) {
  double[] assigned = DriveBiasDistribution.shareRatios(Z, null); // unset bias: plain even split
  boolean[] grounded = {true, false, true, true}; // front-right (index 1) airborne this tick
  double[] load = {NORMAL, 0, NORMAL, NORMAL}; // the other three report the same measured load
  double[] ratios = AxleDriveForceSharing.shareRatios(Z, assigned, grounded, load, sharingEnabled);
  var contacts = new MultiContactFrictionSolver.FrictionContact[]{
    new MultiContactFrictionSolver.FrictionContact(POINTS[0], FORWARD, RIGHT, NORMAL, 1.0, 1.0, 1.0, 0.0, ratios[0]),
    // index 1 (airborne) contributes no FrictionContact at all - PhysicsBridge.stepGround never
    // builds a WheelHit for a missed raycast, the same "no phantom/airborne contribution" shape
    // every other per-wheel budget already has.
    new MultiContactFrictionSolver.FrictionContact(POINTS[2], FORWARD, RIGHT, NORMAL, 1.0, 1.0, 1.0, 0.0, ratios[2]),
    new MultiContactFrictionSolver.FrictionContact(POINTS[3], FORWARD, RIGHT, NORMAL, 1.0, 1.0, 1.0, 0.0, ratios[3]),
  };
  Vector3d velocity = new Vector3d(0,0,0), angular = new Vector3d(0,0,0);
  double driveForcePerWheel = gearSign * TOTAL_DRIVE_FORCE / Z.length;
  MultiContactFrictionSolver.solveFriction(contacts, MASS, INERTIA, IDENTITY, IDENTITY, velocity, angular, 0, driveForcePerWheel, FRICTION, 1.0, DT, 64);
  return velocity.dot(FORWARD);
 }
 @Test void enablingSharingRecoversAccelerationLostToAnAirborneWheelVersusTheDisabledDefault() {
  double disabledSpeed = driveForOneTickWithOneWheelAirborne(false, 1);
  double enabledSpeed = driveForOneTickWithOneWheelAirborne(true, 1);
  assertTrue(enabledSpeed > disabledSpeed,
    "with sharing enabled, the airborne wheel's share must move to its grounded front axle-mate instead of being lost, so acceleration must strictly exceed the disabled default: enabled="+enabledSpeed+" disabled="+disabledSpeed);
 }
 @Test void disabledMatchesThePreTicketBehaviorOfSimplyLosingTheAirborneWheelsShare() {
  double disabledSpeed = driveForOneTickWithOneWheelAirborne(false, 1);
  // Reference: the pre-ticket/no-sharing 3-wheel outcome with each remaining wheel's own
  // unchanged even-split ratio (1.0) - ServiceDriveBiasTest's own missing-contact shape.
  var contacts = new MultiContactFrictionSolver.FrictionContact[]{
    new MultiContactFrictionSolver.FrictionContact(POINTS[0], FORWARD, RIGHT, NORMAL, 1.0, 1.0, 1.0, 0.0, 1.0),
    new MultiContactFrictionSolver.FrictionContact(POINTS[2], FORWARD, RIGHT, NORMAL, 1.0, 1.0, 1.0, 0.0, 1.0),
    new MultiContactFrictionSolver.FrictionContact(POINTS[3], FORWARD, RIGHT, NORMAL, 1.0, 1.0, 1.0, 0.0, 1.0),
  };
  Vector3d velocity = new Vector3d(0,0,0), angular = new Vector3d(0,0,0);
  MultiContactFrictionSolver.solveFriction(contacts, MASS, INERTIA, IDENTITY, IDENTITY, velocity, angular, 0, TOTAL_DRIVE_FORCE/Z.length, FRICTION, 1.0, DT, 64);
  double referenceSpeed = velocity.dot(FORWARD);
  assertEquals(referenceSpeed, disabledSpeed, 1e-9, "disabled sharing must reproduce the exact pre-ticket unredistributed-loss behavior");
 }
 @Test void sharingCompositionStillAcceleratesCorrectlyInReverse() {
  double disabledReverse = driveForOneTickWithOneWheelAirborne(false, -1);
  double enabledReverse = driveForOneTickWithOneWheelAirborne(true, -1);
  assertTrue(disabledReverse < 0.0, "reverse drive must still accelerate the vehicle backward with sharing disabled, got "+disabledReverse);
  assertTrue(enabledReverse < 0.0, "reverse drive must still accelerate the vehicle backward with sharing enabled, got "+enabledReverse);
  assertTrue(enabledReverse < disabledReverse, "the same recovered-acceleration-magnitude effect must hold in reverse (more negative == more backward speed): enabled="+enabledReverse+" disabled="+disabledReverse);
 }
 @Test void driveShareRatioFromSharingStillComposesMultiplicativelyWithSkidSteerMultiplier() {
  double[] assigned = {1.0, 1.0, 1.0, 1.0};
  boolean[] grounded = {true, true, true, true};
  double[] load = {NORMAL, NORMAL*3, NORMAL, NORMAL}; // front-right bears 3x its axle-mate's load
  double[] ratios = AxleDriveForceSharing.shareRatios(Z, assigned, grounded, load, true);
  double driveForcePerWheel = 500; // well under NORMAL*FRICTION so the friction-circle clamp never binds
  double skidSteerMultiplier = 0.7;
  var contacts = new MultiContactFrictionSolver.FrictionContact[]{
    new MultiContactFrictionSolver.FrictionContact(POINTS[1], FORWARD, RIGHT, NORMAL, skidSteerMultiplier, 1.0, 1.0, 0.0, ratios[1]),
  };
  Vector3d velocity = new Vector3d(0,0,0), angular = new Vector3d(0,0,0);
  var results = MultiContactFrictionSolver.solveFriction(contacts, MASS, INERTIA, IDENTITY, IDENTITY, velocity, angular, 0, driveForcePerWheel, FRICTION, 1.0, DT, 1);
  double expected = driveForcePerWheel * skidSteerMultiplier * ratios[1];
  assertEquals(expected, results[0].longitudinal(), 1e-6, "sharing's own redistributed ratio must compose multiplicatively with skid-steer, neither overriding the other");
 }
}
