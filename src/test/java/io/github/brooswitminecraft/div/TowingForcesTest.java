package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/**
 * Verifies TowingForces.impulse(...) against the observable velocity delta it must reproduce -
 * IV's own motion.addScaled(totalForce, 1/currentMass), converted through PhysicsBridge's
 * established ivMotion<->sableVelocity factor (speedFactor * 20) - not just "some multiplier".
 */
class TowingForcesTest {
 @Test void noTowedThrustMeansNoImpulse() { assertEquals(0, TowingForces.impulse(0,.35,1000,1000)); }

 @Test void equalMassesReproduceIvsVelocityDelta() {
  // The regression this review flagged: at IV's default car speedFactor 0.35, a towed engine
  // thrust of 1000 must raise Sable velocity by exactly what IV's own
  // motion.addScaled(totalForce, 1/currentMass) then *speedFactor*20 would - 7000, not 50.
  double thrust = 1000, speedFactor = .35, mass = 1500;
  double expectedVelocityDelta = (thrust/mass) * speedFactor * 20;
  double impulse = TowingForces.impulse(thrust, speedFactor, mass, mass);
  assertEquals(expectedVelocityDelta, impulse/mass, 1e-9);
  assertEquals(7000.0/1500.0, impulse/mass, 1e-9);
 }

 @Test void nonDefaultSpeedFactorScalesLinearly() {
  assertEquals(2*TowingForces.impulse(1000,.35,1000,1000), TowingForces.impulse(1000,.70,1000,1000), 1e-9);
 }

 @Test void massOverrideStillMatchesIvsVelocityDelta() {
  // A tuning.massKg override makes the Sable body's own mass diverge from IV's currentMass; the
  // impulse must still reproduce the SAME velocity delta IV's own vehicle would have gained, not
  // the delta an unadjusted impulse would give this body's (different) mass.
  double thrust = 1000, speedFactor = .35, ivMass = 1500, sableMass = 3000;
  double expectedVelocityDelta = (thrust/ivMass) * speedFactor * 20;
  double impulse = TowingForces.impulse(thrust, speedFactor, sableMass, ivMass);
  assertEquals(expectedVelocityDelta, impulse/sableMass, 1e-9);
 }

 @Test void negativeThrustStaysNegative() { assertTrue(TowingForces.impulse(-500,.35,1000,1000) < 0); }
}
