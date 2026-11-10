package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Review follow-up on MINECRAFT-503: the first pass only demonstrated the rollover/inertia root
 * cause, leaving the separately reported ride-height "sinks into the ground a bit" unproven
 * either way. {@code Body.stepGround} cannot be driven directly without a live Minecraft/IV/Sable
 * runtime (see VERIFICATION.md) - it reads the actual raycast hit, the live entity's parts/inputs,
 * and a real Sable rigid-body handle. What CAN be tested without that runtime is the exact
 * per-wheel vertical dynamics {@code stepGround} actually computes every tick: gravity, the
 * production {@link PhysicsBridge#suspensionSpring}/{@link PhysicsBridge#suspensionDamper}
 * constants, and the production {@link TireForces#suspension} force law, integrated the same way
 * a physics engine would (semi-implicit/symplectic Euler, at this project's fixed {@code DT}).
 *
 * <p>This found a real, confirmed root cause, not just a clean bill of health: this class
 * previously hard-coded {@code 9.81} for both the per-wheel spring calibration and the normal
 * clamp, but the exact pinned dependency's own default gravity (bytecode-verified against
 * {@code sable-neoforge-1.21.1:2.0.5}'s {@code DimensionPhysics.DEFAULT_GRAVITY}) is
 * {@code (0,-11,0)} - a Sable rigid body actually falls under 11 m/s^2, not 9.81.
 * {@link #mismatchedGravityCalibrationUndersupportsTheVehicleBeforeTheFix} reproduces exactly
 * that mismatch and shows it settles measurably BELOW the intended half-travel equilibrium -
 * real, persistent, shared (not ATV-specific) sinking. The production fix now reads the body's
 * actual level gravity via {@link PhysicsBridge#gravityMagnitude} and calibrates the spring with
 * it instead of a hard-coded Earth constant; the remaining tests exercise that corrected,
 * gravity-matched path and confirm it settles exactly at the predicted equilibrium across
 * representative vehicles, starting conditions, and without divergence/unbounded oscillation.
 */
class PhysicsBridgeRideHeightSimulationTest {
 private static final double TRAVEL = 0.3, TOLERANCE = 1e-6;
 /** One semi-implicit Euler tick of the exact per-wheel vertical dynamics stepGround applies:
  * velocity updates from gravity minus the per-wheel suspension share, then compression updates
  * from the now-current velocity (matching a physics engine's typical integration order, and
  * stepGround's own "velocity sampled this tick, impulse applied this tick" structure).
  * {@code bodyGravity} is the actual acceleration gravity applies to the body every tick (what a
  * live Sable rigid body actually falls under); {@code calibrationGravity} is what the spring/
  * damper were calibrated for. Production code always has these equal (gravityMagnitude feeds
  * both); the mismatch test below deliberately decouples them to reproduce the pre-fix bug. */
 private static double[] tick(double compression, double velocity, double mass, int wheelCount, double spring, double damper, double bodyGravity) {
  double perWheelNormal = Math.min(mass*bodyGravity, TireForces.suspension(compression, velocity, spring, damper));
  double netAcceleration = -bodyGravity + (perWheelNormal*wheelCount)/mass;
  double newVelocity = velocity + PhysicsBridge.DT*netAcceleration;
  double newCompression = compression - PhysicsBridge.DT*newVelocity;
  return new double[]{newCompression, newVelocity};
 }
 private static double settle(double compression, double velocity, double mass, int wheelCount, int ticks, double calibrationGravity, double bodyGravity) {
  double spring = PhysicsBridge.suspensionSpring(mass, wheelCount, TRAVEL, calibrationGravity);
  double damper = PhysicsBridge.suspensionDamper(mass, wheelCount, spring);
  for (int i=0; i<ticks; i++) {
   var next = tick(compression, velocity, mass, wheelCount, spring, damper, bodyGravity);
   compression = next[0]; velocity = next[1];
  }
  return compression;
 }
 @Test void mismatchedGravityCalibrationUndersupportsTheVehicleBeforeTheFix() {
  // Reproduces the exact pre-fix bug: spring calibrated for a hard-coded 9.81 while the body
  // actually falls under Sable's real default, 11. Algebraically this settles at
  // travel*.5*(11/9.81), about 18mm (6.07%) deeper than the intended half-travel equilibrium -
  // this simulation confirms the full forward-time integration actually converges there too,
  // not just the one-shot algebra.
  double settled = settle(TRAVEL*.5, 0, 1500, 4, 300, 9.81, 11);
  double expectedMismatchedEquilibrium = TRAVEL*.5*(11/9.81);
  assertEquals(expectedMismatchedEquilibrium, settled, 1e-4,
    "mismatched calibration must settle deeper than the intended equilibrium, reproducing the reported sinking");
  assertTrue(settled > TRAVEL*.5 + 0.015,
    "sinking must be a real, measurable amount (~18mm at this travel), not noise");
 }
 @Test void restingAtPredictedEquilibriumStaysThereAcrossRepresentativeVehiclesAndGravities() {
  // ATV-like (4 wheels, light), car-like (4 wheels, mid), truck-like (6 wheels, heavy), and a
  // tracked/irregular 2-ground-device layout - the exact spread the ticket's acceptance demands
  // ("wheel radius, wheel count, track width, wheelbase, mass and CG"; mass/wheelCount is what
  // this 1-DOF vertical model actually depends on, independent of layout). Checked at both
  // Sable's actual default (11) and Earth gravity (9.81), to confirm the fixed, gravity-matched
  // calibration is correct regardless of which a given dimension's datapack configures.
  for (double gravity : new double[]{11, 9.81}) {
   for (var vehicle : new double[][]{{300,4},{1500,4},{8000,6},{1200,2}}) {
    double mass=vehicle[0]; int wheelCount=(int)vehicle[1];
    double settled = settle(TRAVEL*.5, 0, mass, wheelCount, 200, gravity, gravity);
    assertEquals(TRAVEL*.5, settled, TOLERANCE,
      "mass="+mass+" wheels="+wheelCount+" gravity="+gravity+" must stay at the predicted equilibrium when calibration matches actual gravity");
   }
  }
 }
 @Test void hardLandingFromZeroCompressionConvergesToThePredictedEquilibriumNotSomewhereElse() {
  // A harder, more representative starting condition: the wheel just made contact with zero
  // compression and some downward speed (a landing/contact-regain case the ticket's acceptance
  // explicitly lists). At Sable's actual gravity (11), with the now gravity-matched calibration.
  for (var vehicle : new double[][]{{300,4},{1500,4},{8000,6}}) {
   double mass=vehicle[0]; int wheelCount=(int)vehicle[1];
   double settled = settle(0, -3, mass, wheelCount, 400, 11, 11);
   assertEquals(TRAVEL*.5, settled, 1e-3,
     "mass="+mass+" wheels="+wheelCount+" must converge to the predicted equilibrium after landing, not sink or float past it");
  }
 }
 @Test void doesNotOscillateForeverOrDiverge() {
  // A sanity bound distinct from the convergence value itself: compression must stay within the
  // physically available travel band (never negative - that would mean the wheel left the
  // ground while "resting" - and never beyond full travel, i.e. never a runaway/divergent
  // integration), confirming settling is damped rather than merely eventually-correct-by-luck.
  double mass=1500; int wheelCount=4, gravity=11;
  double spring = PhysicsBridge.suspensionSpring(mass, wheelCount, TRAVEL, gravity);
  double damper = PhysicsBridge.suspensionDamper(mass, wheelCount, spring);
  double compression=0, velocity=-3;
  for (int i=0; i<400; i++) {
   var next = tick(compression, velocity, mass, wheelCount, spring, damper, gravity);
   compression = next[0]; velocity = next[1];
   assertTrue(compression >= -TOLERANCE && compression <= TRAVEL+TOLERANCE,
     "tick "+i+": compression "+compression+" left the physical travel band - divergent or unbounded oscillation");
  }
 }
}
