package io.github.brooswitminecraft.div;
import io.github.brooswitminecraft.div.experimental.MultiContactFrictionSolver;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/**
 * MINECRAFT-524: covers the ticket's own named acceptance shape for the configurable front/rear
 * drive-force bias, as a focused real-production-path integration against the actual
 * {@link MultiContactFrictionSolver#solveFriction}, combining {@link DriveBiasDistribution}'s
 * geometric shares with the solver's own {@code driveForcePerWheel*skidSteerMultiplier*
 * driveShareRatio} composition exactly as {@code PhysicsBridge.stepGround} does, on a
 * representative 4-wheel layout - conservation, front/rear extremes, odd/asymmetric wheel
 * counts, reverse travel, missing-contact behavior, and default (unset bias) equivalence.
 * Deliberately mirrors {@link ServiceBrakeBiasTest}'s own solver-level shape.
 */
class ServiceDriveBiasTest {
 private static final double MASS = 1500, DT = 0.05, FRICTION = 1.0, NORMAL = 3750, TOTAL_DRIVE_FORCE = 8000;
 private static final Vector3d INERTIA = new Vector3d(600, 900, 1400);
 private static final Quaterniond IDENTITY = new Quaterniond();
 private static final Vector3d FORWARD = new Vector3d(0, 0, 1), RIGHT = new Vector3d(1, 0, 0);
 // Representative 4-wheel layout: front axle at z=+1.5, rear axle at z=-1.5 (same shape as
 // WheelLoadDistributionTest/BrakeBiasDistributionTest/ServiceBrakeBiasTest's own symmetric
 // 4-wheel case).
 private static final Vector3d[] POINTS = {
   new Vector3d(1.0, -0.4, 1.5), new Vector3d(-1.0, -0.4, 1.5),
   new Vector3d(1.0, -0.4, -1.5), new Vector3d(-1.0, -0.4, -1.5),
 };
 private static final double[] Z = {1.5, 1.5, -1.5, -1.5};
 /** The exact per-wheel DRIVE-force ratio {@code PhysicsBridge.stepGround} computes - share
  * (from {@link DriveBiasDistribution#shares}) times wheel count, composing directly with the
  * solver's own {@code driveForcePerWheel} multiply. */
 private static double[] driveShareRatiosFromBias(Double biasFront) {
  double[] shares = DriveBiasDistribution.shares(Z, biasFront);
  double[] ratios = new double[Z.length];
  for (int i = 0; i < Z.length; i++) ratios[i] = shares[i] * Z.length;
  return ratios;
 }
 @Test void conservationHoldsAcrossSeveralBiasValues() {
  for (double bias : new double[]{0.0, 0.25, 0.5, 0.75, 1.0}) {
   double[] ratios = driveShareRatiosFromBias(bias);
   double total = 0; for (double r : ratios) total += r * (TOTAL_DRIVE_FORCE / Z.length);
   assertEquals(TOTAL_DRIVE_FORCE, total, 1e-6, "bias="+bias+": the full configured drive-force budget must be preserved exactly");
  }
 }
 @Test void unsetBiasMatchesThePreTicketUniformPerWheelRatioExactly() {
  double[] ratios = driveShareRatiosFromBias(null);
  for (double r : ratios) assertEquals(1.0, r, 1e-12, "unset bias must be the pure no-op ratio (1.0) for every wheel, reproducing the plain driveForce/wheelCount budget");
 }
 @Test void fullFrontBiasDrivesOnlyTheFrontAxleAndStillAcceleratesFromRest() {
  double[] ratios = driveShareRatiosFromBias(1.0);
  assertEquals(0.0, ratios[2], 1e-12); assertEquals(0.0, ratios[3], 1e-12); // rear wheels get no drive ratio at all
  double finalSpeed = driveForOneTick(0.0, ratios, 64);
  assertTrue(finalSpeed > 0.0, "front-only drive must still accelerate the vehicle forward from rest, got "+finalSpeed);
 }
 @Test void fullRearBiasDrivesOnlyTheRearAxleAndStillAcceleratesFromRest() {
  double[] ratios = driveShareRatiosFromBias(0.0);
  assertEquals(0.0, ratios[0], 1e-12); assertEquals(0.0, ratios[1], 1e-12); // front wheels get no drive ratio at all
  double finalSpeed = driveForOneTick(0.0, ratios, 64);
  assertTrue(finalSpeed > 0.0, "rear-only drive must still accelerate the vehicle forward from rest, got "+finalSpeed);
 }
 @Test void biasedDriveStillAcceleratesCorrectlyInReverse() {
  // A negative driveForcePerWheel (reverse gear - PhysicsBridge.stepGround's own
  // Math.signum(engine.currentGearVar.currentValue) flip) must still accelerate the vehicle
  // BACKWARD regardless of the configured bias - the bias only changes HOW the budget splits
  // across wheels, never which direction drive acts.
  for (double bias : new double[]{0.0, 0.3, 0.5, 0.7, 1.0}) {
   double[] ratios = driveShareRatiosFromBias(bias);
   double finalSpeed = driveForOneTick(0.0, ratios, 64, -1);
   assertTrue(finalSpeed < 0.0, "bias="+bias+": reverse drive must still accelerate the vehicle backward, got "+finalSpeed);
  }
 }
 @Test void asymmetricAxleWheelCountsStillConserveTotalDriveBudget() {
  // One front wheel (a trike-like front end), two rear wheels - same shape as
  // DriveBiasDistributionTest's own asymmetric case, verified again here at the ratio level
  // actually fed to the solver.
  double[] z = {2.0, -1.0, -1.0};
  double[] shares = DriveBiasDistribution.shares(z, 0.7);
  double[] ratios = new double[3];
  for (int i = 0; i < 3; i++) ratios[i] = shares[i] * z.length;
  double total = 0; for (int i = 0; i < 3; i++) total += ratios[i] * (TOTAL_DRIVE_FORCE / z.length);
  assertEquals(TOTAL_DRIVE_FORCE, total, 1e-6);
  assertEquals(0.7 * 3, ratios[0], 1e-9, "the lone front wheel's ratio carries the WHOLE front share, expressed as a ratio");
 }
 @Test void aWheelMissingItsGroundContactThisTickSimplyLosesItsOwnShareRatherThanRedistributingIt() {
  // Shares are computed from the full wheel geometry (DriveBiasDistribution.shares over all 4
  // Z positions), independent of which wheels are actually grounded this tick - the same
  // conservative, unredistributed-loss behavior every other per-wheel budget (suspension,
  // brake) already has for a missing contact. Dropping one wheel's own FrictionContact from the
  // list passed to the solver (as PhysicsBridge.stepGround's own `hits` naturally does when a
  // raycast misses) must not inflate any other wheel's own ratio to compensate.
  double[] ratios = driveShareRatiosFromBias(0.5); // neutral bias: every wheel ratio == 1.0
  for (double r : ratios) assertEquals(1.0, r, 1e-12);
  // Only 3 of the 4 wheels grounded this tick (front-right missing) - the other three keep
  // their own unchanged ratio; nothing here re-derives a 3-wheel share.
  var contacts = new MultiContactFrictionSolver.FrictionContact[]{
    new MultiContactFrictionSolver.FrictionContact(POINTS[1], FORWARD, RIGHT, NORMAL, 1.0, 1.0, 1.0, 0.0, ratios[1]),
    new MultiContactFrictionSolver.FrictionContact(POINTS[2], FORWARD, RIGHT, NORMAL, 1.0, 1.0, 1.0, 0.0, ratios[2]),
    new MultiContactFrictionSolver.FrictionContact(POINTS[3], FORWARD, RIGHT, NORMAL, 1.0, 1.0, 1.0, 0.0, ratios[3]),
  };
  Vector3d velocity = new Vector3d(0, 0, 0), angular = new Vector3d(0, 0, 0);
  double driveForcePerWheel = TOTAL_DRIVE_FORCE / Z.length;
  MultiContactFrictionSolver.solveFriction(contacts, MASS, INERTIA, IDENTITY, IDENTITY, velocity, angular, 0, driveForcePerWheel, FRICTION, 1.0, DT, 64);
  assertTrue(velocity.dot(FORWARD) > 0.0, "the three still-grounded wheels must still accelerate the vehicle forward with the missing wheel's own drive share simply gone, got "+velocity.dot(FORWARD));
 }
 private static double driveForOneTick(double initialSpeed, double[] ratios, int iterations) {
  return driveForOneTick(initialSpeed, ratios, iterations, 1);
 }
 /** Drives the real solver for one tick with each wheel's own drive-bias ratio folded directly
  * into its {@code FrictionContact.driveShareRatio()}, the exact way {@code stepGround} does,
  * then returns the resulting COM-forward speed. {@code gearSign} is +1 (forward) or -1
  * (reverse), mirroring {@code Math.signum(engine.currentGearVar.currentValue)}. */
 private static double driveForOneTick(double initialSpeed, double[] ratios, int iterations, int gearSign) {
  Vector3d velocity = new Vector3d(FORWARD).mul(initialSpeed);
  Vector3d angular = new Vector3d(0, 0, 0);
  double driveForcePerWheel = gearSign * TOTAL_DRIVE_FORCE / ratios.length;
  var contacts = new MultiContactFrictionSolver.FrictionContact[ratios.length];
  for (int i = 0; i < ratios.length; i++) {
   contacts[i] = new MultiContactFrictionSolver.FrictionContact(POINTS[i], FORWARD, RIGHT, NORMAL, 1.0, 1.0, 1.0, 0.0, ratios[i]);
  }
  MultiContactFrictionSolver.solveFriction(contacts, MASS, INERTIA, IDENTITY, IDENTITY, velocity, angular, 0, driveForcePerWheel, FRICTION, 1.0, DT, iterations);
  return velocity.dot(FORWARD);
 }
}
