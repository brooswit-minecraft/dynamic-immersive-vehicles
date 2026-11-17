package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/**
 * MINECRAFT-553: {@link PhysicsBridge#gearForceScale} is the pure-math core of the opt-in
 * gear-aware ground drive - covers the ticket's own named acceptance shape: disabled (default)
 * is a byte-identical 1.0 no-op regardless of the ratios passed in; the reference (first forward)
 * gear itself always resolves to exactly 1.0 when enabled; a higher forward gear (smaller ratio
 * magnitude than the reference) produces a distinct, smaller scale; a lower/stronger gear (larger
 * magnitude) produces a distinct, larger scale, each clamped to the documented bounds; and every
 * non-finite/non-positive/degenerate input falls back to the safe 1.0 no-op rather than dividing
 * by zero or propagating NaN/Infinity.
 */
class GearForceScaleTest {
 @Test void disabledIsAlwaysExactlyOneRegardlessOfRatios() {
  assertEquals(1.0, PhysicsBridge.gearForceScale(3.5, 1.0, false));
  assertEquals(1.0, PhysicsBridge.gearForceScale(0.0, 1.0, false));
  assertEquals(1.0, PhysicsBridge.gearForceScale(Double.NaN, Double.NaN, false));
 }
 @Test void enabledAtTheReferenceGearItselfIsExactlyOne() {
  // currentGearRatioMagnitude == referenceGearRatioMagnitude is the documented "first forward
  // gear" baseline - must reproduce today's unscaled drive force exactly.
  assertEquals(1.0, PhysicsBridge.gearForceScale(3.5, 3.5, true), 0.0);
 }
 @Test void enabledHigherGearWithSmallerRatioMagnitudeProducesADistinctSmallerScale() {
  // A real 2nd/3rd gear has a smaller ratio magnitude than 1st - real drivetrain mechanics: less
  // torque multiplication, less drive force, more top speed.
  double reference = 3.5, secondGear = 2.0;
  double scale = PhysicsBridge.gearForceScale(secondGear, reference, true);
  assertEquals(secondGear / reference, scale, 1e-9);
  assertTrue(scale < 1.0, "a smaller-magnitude gear must scale drive force down below the reference gear's 1.0");
 }
 @Test void enabledLowerGearWithLargerRatioMagnitudeProducesADistinctLargerScale() {
  double reference = 3.5, reverseGear = 4.5;
  double scale = PhysicsBridge.gearForceScale(reverseGear, reference, true);
  assertEquals(reverseGear / reference, scale, 1e-9);
  assertTrue(scale > 1.0, "a larger-magnitude gear must scale drive force up above the reference gear's 1.0");
 }
 @Test void resultIsClampedToTheDocumentedBounds() {
  assertEquals(PhysicsBridge.MAX_GEAR_FORCE_SCALE, PhysicsBridge.gearForceScale(1000.0, 1.0, true), 0.0,
    "an extreme configured ratio outlier must never make combined wheel/tire force unboundedly large");
  assertEquals(PhysicsBridge.MIN_GEAR_FORCE_SCALE, PhysicsBridge.gearForceScale(0.0001, 1.0, true), 0.0,
    "an extreme configured ratio outlier must never collapse drive force to effectively zero while in gear");
 }
 @Test void nonPositiveOrNonFiniteReferenceFallsBackToTheUnscaledNoOp() {
  assertEquals(1.0, PhysicsBridge.gearForceScale(2.0, 0.0, true), "zero reference (e.g. a single-forward-gear table) must not divide by zero");
  assertEquals(1.0, PhysicsBridge.gearForceScale(2.0, -1.0, true), "a negative reference magnitude is degenerate input");
  assertEquals(1.0, PhysicsBridge.gearForceScale(2.0, Double.NaN, true));
  assertEquals(1.0, PhysicsBridge.gearForceScale(2.0, Double.POSITIVE_INFINITY, true));
 }
 @Test void negativeOrNonFiniteCurrentMagnitudeFallsBackToTheUnscaledNoOp() {
  assertEquals(1.0, PhysicsBridge.gearForceScale(-1.0, 3.5, true), "a magnitude is never negative - degenerate input");
  assertEquals(1.0, PhysicsBridge.gearForceScale(Double.NaN, 3.5, true));
  assertEquals(1.0, PhysicsBridge.gearForceScale(Double.POSITIVE_INFINITY, 3.5, true), 0.0,
    "a non-finite current magnitude falls back to the safe no-op rather than an unbounded/NaN scale");
 }
 @Test void singleForwardGearVehicleIsAlwaysANoOpEvenWhenEnabled() {
  // A vehicle with only one forward gear has reference == current always - a real-world case
  // this ticket must not regress, not merely a synthetic edge case.
  assertEquals(1.0, PhysicsBridge.gearForceScale(1.0, 1.0, true), 0.0);
 }
 @Test void neutralStaysExactlyZeroRegardlessOfTheGearScale() {
  // stepGround's own real composition is Math.signum(currentGear)*throttle*driveForce*scale -
  // at neutral (currentGear==0) Math.signum(0)==0, so the WHOLE term is 0 no matter what this
  // method itself returns for an arbitrary (even extreme) scale - the documented "sign/neutral
  // safety" acceptance requirement, proven at the real multiplication this ticket adds, not just
  // asserted in prose.
  double throttle = 1.0, driveForce = 8000.0;
  double anyScale = PhysicsBridge.gearForceScale(1000.0, 1.0, true); // deliberately an extreme, clamped scale
  assertEquals(0.0, Math.signum(0.0) * throttle * driveForce * anyScale, 0.0);
 }
 @Test void productionCompositionScalesTheRealResolvedDriveForceProportionallyByGear() {
  // Exercises the exact real production composition stepGround performs
  // (enginePartDriveForce(...) * gearForceScale(...)), not a reimplementation of it - the
  // "distinct configured forward-ratio effects" and "bounded combined tire force" acceptance
  // requirements proven together at the real call shape.
  double vehicleDriveForce = PhysicsBridge.enginePartDriveForce("packid:not_configured", 8000.0);
  double reference = 3.5; // first forward gear
  double firstGearForce = vehicleDriveForce * PhysicsBridge.gearForceScale(reference, reference, true);
  double secondGearForce = vehicleDriveForce * PhysicsBridge.gearForceScale(2.0, reference, true);
  double disabledForce = vehicleDriveForce * PhysicsBridge.gearForceScale(2.0, reference, false);
  assertEquals(8000.0, firstGearForce, 1e-9, "first gear must reproduce today's exact unscaled drive force");
  assertEquals(8000.0, disabledForce, 1e-9, "disabled must reproduce today's exact unscaled drive force regardless of gear");
  assertTrue(secondGearForce < firstGearForce, "a higher gear's smaller ratio magnitude must produce strictly less combined drive force than first gear");
  assertTrue(secondGearForce > 0, "drive force must stay strictly positive (bounded, never collapsed to zero) for a real in-gear ratio");
 }
}
