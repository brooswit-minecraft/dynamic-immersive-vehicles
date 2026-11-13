package io.github.brooswitminecraft.div;
import io.github.brooswitminecraft.div.experimental.MultiContactFrictionSolver;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/**
 * MINECRAFT-520: covers the ticket's own named acceptance shape for the configurable front/rear
 * service-brake bias, at two levels:
 * <ul>
 * <li>{@link PhysicsBridge#perWheelBrakeMagnitude} directly - the pure, testable composition
 * seam {@code stepGround} actually calls per wheel (parking-brake independence, handbrake
 * independence, proportional service-pedal scaling, and the "unset/fallback ratio is a pure
 * no-op" equivalence).</li>
 * <li>A focused real-production-path integration against the actual
 * {@link MultiContactFrictionSolver#solveFriction}, combining {@link BrakeBiasDistribution}'s
 * geometric shares with {@link PhysicsBridge#perWheelBrakeMagnitude} exactly as {@code
 * stepGround} does, on a representative 4-wheel layout - conservation, front/rear extremes, and
 * reverse travel.</li>
 * </ul>
 */
class ServiceBrakeBiasTest {
 // --- perWheelBrakeMagnitude: the pure per-wheel composition seam ---
 @Test void parkingBrakeLocksEveryWheelFullyRegardlessOfBiasRatioOrHandbrake() {
  // Even an extreme bias ratio (as if this wheel got zero service-brake share) or zero
  // handbrake must not weaken the parking brake's own full, uniform lock.
  assertEquals(1.0, PhysicsBridge.perWheelBrakeMagnitude(true, true, 0.0, 0.0, 0.0));
  assertEquals(1.0, PhysicsBridge.perWheelBrakeMagnitude(false, true, 1.0, 0.0, 2.0));
  assertEquals(1.0, PhysicsBridge.perWheelBrakeMagnitude(false, true, 0.37, 0.9, 0.0));
 }
 @Test void unsetOrFallbackRatioOfOneReproducesThePreTicketFormulaExactly() {
  // ratio==1.0 is BrakeBiasDistribution's own documented "no bias configured" value - must be a
  // pure no-op, byte-identical to the pre-MINECRAFT-520 max(service,handbrake)/service-only split.
  assertEquals(0.6, PhysicsBridge.perWheelBrakeMagnitude(true, false, 0.6, 0.0, 1.0));
  assertEquals(0.6, PhysicsBridge.perWheelBrakeMagnitude(false, false, 0.6, 0.0, 1.0));
  assertEquals(0.9, PhysicsBridge.perWheelBrakeMagnitude(false, false, 0.6, 0.9, 1.0),
    "non-steering wheel must still carry max(service,handbrake) when the bias ratio is a no-op");
 }
 @Test void servicePedalMagnitudeScalesProportionallyWithTheBiasRatio() {
  assertEquals(0.3, PhysicsBridge.perWheelBrakeMagnitude(true, false, 0.3, 0.0, 1.0), 1e-12);
  assertEquals(0.6, PhysicsBridge.perWheelBrakeMagnitude(true, false, 0.3, 0.0, 2.0), 1e-12, "doubling the ratio must double the biased service component");
  assertEquals(0.15, PhysicsBridge.perWheelBrakeMagnitude(true, false, 0.3, 0.0, 0.5), 1e-12);
  assertEquals(0.0, PhysicsBridge.perWheelBrakeMagnitude(true, false, 0.3, 0.0, 0.0), 1e-12, "a wheel excluded entirely from this axle group gets no service-brake contribution at all");
 }
 @Test void handbrakeIsNeverScaledByTheServiceBrakeBias() {
  // A rear/handbrake-capable wheel with NO service pedal pressed (biasedService=0) must still
  // get the handbrake's own full, unscaled magnitude, same as a ratio of exactly 1.0 would.
  double withTinyServiceShare = PhysicsBridge.perWheelBrakeMagnitude(false, false, 1.0, 0.8, 0.01);
  assertEquals(0.8, withTinyServiceShare, 1e-12, "the handbrake's own 0.8 must win over the heavily-biased-down service component (0.01)");
  double withNoServiceAtAll = PhysicsBridge.perWheelBrakeMagnitude(false, false, 0.0, 0.8, 5.0);
  assertEquals(0.8, withNoServiceAtAll, 1e-12);
 }
 @Test void steeringWheelNeverCarriesHandbrakeRegardlessOfBiasRatio() {
  assertEquals(0.4, PhysicsBridge.perWheelBrakeMagnitude(true, false, 0.2, 0.9, 2.0), 1e-12, "a steering wheel's own magnitude must come from the biased SERVICE component alone, never the handbrake");
 }

 // --- Real production-path integration: BrakeBiasDistribution + perWheelBrakeMagnitude + the actual solver ---
 private static final double MASS = 1500, DT = 0.05, FRICTION = 1.0, NORMAL = 3750, TOTAL_BRAKE_FORCE = 12000;
 private static final Vector3d INERTIA = new Vector3d(600, 900, 1400);
 private static final Quaterniond IDENTITY = new Quaterniond();
 private static final Vector3d FORWARD = new Vector3d(0, 0, 1), RIGHT = new Vector3d(1, 0, 0);
 // Representative 4-wheel layout: front axle at z=+1.5, rear axle at z=-1.5 (same shape as
 // WheelLoadDistributionTest/BrakeBiasDistributionTest's own symmetric 4-wheel case).
 private static final Vector3d[] POINTS = {
   new Vector3d(1.0, -0.4, 1.5), new Vector3d(-1.0, -0.4, 1.5),
   new Vector3d(1.0, -0.4, -1.5), new Vector3d(-1.0, -0.4, -1.5),
 };
 private static final double[] Z = {1.5, 1.5, -1.5, -1.5};
 private static double[] perWheelCapsFromBias(Double biasFront) {
  double[] shares = BrakeBiasDistribution.shares(Z, biasFront);
  double baseline = TOTAL_BRAKE_FORCE / Z.length;
  double[] caps = new double[Z.length];
  for (int i = 0; i < Z.length; i++) caps[i] = baseline * (shares[i] * Z.length);
  return caps;
 }
 @Test void conservationHoldsAcrossSeveralBiasValues() {
  for (double bias : new double[]{0.0, 0.25, 0.5, 0.75, 1.0}) {
   double[] caps = perWheelCapsFromBias(bias);
   double total = 0; for (double c : caps) total += c;
   assertEquals(TOTAL_BRAKE_FORCE, total, 1e-6, "bias="+bias+": the full configured brakeForce budget must be preserved exactly");
  }
 }
 @Test void unsetBiasMatchesThePreTicketUniformPerWheelCapExactly() {
  double[] caps = perWheelCapsFromBias(null);
  for (double c : caps) assertEquals(TOTAL_BRAKE_FORCE / 4, c, 1e-9, "unset bias must reproduce the plain brakeForce/wheelCount cap for every wheel");
 }
 @Test void fullFrontBiasBraksOnlyTheFrontAxleAndStillDeceleratesForwardTravel() {
  double[] caps = perWheelCapsFromBias(1.0);
  assertEquals(0.0, caps[2], 1e-9); assertEquals(0.0, caps[3], 1e-9); // rear wheels get no service-brake cap at all
  double finalSpeed = brakeToRest(5.0, caps, 64);
  assertTrue(finalSpeed < 5.0 && finalSpeed >= 0, "front-only braking must still slow the vehicle down from forward travel, got "+finalSpeed);
 }
 @Test void fullRearBiasBraksOnlyTheRearAxleAndStillDeceleratesForwardTravel() {
  double[] caps = perWheelCapsFromBias(0.0);
  assertEquals(0.0, caps[0], 1e-9); assertEquals(0.0, caps[1], 1e-9); // front wheels get no service-brake cap at all
  double finalSpeed = brakeToRest(5.0, caps, 64);
  assertTrue(finalSpeed < 5.0 && finalSpeed >= 0, "rear-only braking must still slow the vehicle down from forward travel, got "+finalSpeed);
 }
 @Test void biasedBrakingStillDeceleratesCorrectlyDuringReverseTravel() {
  // Reverse travel (negative initial speed) must still brake TOWARD zero (speed must increase,
  // i.e. move up toward 0), for an asymmetric bias exactly the same as it does unbiased - the
  // bias only changes HOW the budget is split across wheels, never which direction braking acts.
  for (double bias : new double[]{0.0, 0.3, 0.5, 0.7, 1.0}) {
   double[] caps = perWheelCapsFromBias(bias);
   double finalSpeed = brakeToRest(-5.0, caps, 64);
   assertTrue(finalSpeed > -5.0 && finalSpeed <= 0, "bias="+bias+": reverse travel must still brake toward zero, got "+finalSpeed);
  }
 }
 /** Drives the real solver for one tick with each wheel's own per-wheel cap folded into its
  * {@code brakeMagnitude} the exact way {@code stepGround}'s {@code perWheelBrakeMagnitude} call
  * does (ratio = cap/baseline, composed with a full service-pedal press), then returns the
  * resulting COM-forward speed. */
 private static double brakeToRest(double initialSpeed, double[] perWheelCaps, int iterations) {
  Vector3d velocity = new Vector3d(FORWARD).mul(initialSpeed);
  Vector3d angular = new Vector3d(0, 0, 0);
  double baseline = TOTAL_BRAKE_FORCE / perWheelCaps.length;
  var contacts = new MultiContactFrictionSolver.FrictionContact[perWheelCaps.length];
  for (int i = 0; i < perWheelCaps.length; i++) {
   double ratio = perWheelCaps[i] / baseline;
   double magnitude = PhysicsBridge.perWheelBrakeMagnitude(false, false, 1.0, 0.0, ratio);
   contacts[i] = new MultiContactFrictionSolver.FrictionContact(POINTS[i], FORWARD, RIGHT, NORMAL, 1.0, magnitude);
  }
  MultiContactFrictionSolver.solveFriction(contacts, MASS, INERTIA, IDENTITY, IDENTITY, velocity, angular, baseline, 0, FRICTION, 1.0, DT, iterations);
  return velocity.dot(FORWARD);
 }
}
