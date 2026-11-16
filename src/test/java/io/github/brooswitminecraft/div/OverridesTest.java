package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Overrides.load() reads FML's config directory and cannot run outside a modded runtime, so
 * these tests exercise what can run as a plain unit test: the opt-out lookup against the
 * default (no config file loaded) state, and the tuning validator's own range checks. The
 * loader's file I/O and JSON parsing remain covered only by the runtime-smoke/acceptance path.
 */
class OverridesTest {
 @Test void unknownKeyIsNotOptedOutAndHasNoEntry() {
  assertNull(Overrides.get("packid:not_configured"));
  assertFalse(Overrides.isOptedOut("packid:not_configured"));
 }

 @Test void defaultTuningValidatesAndIsSeparateFromOverrideEntries() {
  Overrides.defaultTuning().validate();
  assertNull(Overrides.defaultTuning().enabled);
 }

 @Test void defaultTuningReturnsAnIndependentCopyEveryCallNotTheSharedSingleton() {
  // Comment 53184: the earlier public DEFAULT field exposed the shared FALLBACK instance
  // directly - any caller mutating it would corrupt every other vehicle's resolution. Mutating
  // one returned copy must never affect another.
  var first = Overrides.defaultTuning();
  first.driveForce = 1.0;
  var second = Overrides.defaultTuning();
  assertEquals(8000.0, second.driveForce, "mutating one defaultTuning() copy must not affect a later call's copy");
 }

 @Test void enabledIsNullableSoBothTrueFalseAndUnsetValidate() {
  var unset = new Overrides.Tuning();
  var optedIn = new Overrides.Tuning();
  optedIn.enabled = true;
  var optedOut = new Overrides.Tuning();
  optedOut.enabled = false;
  assertDoesNotThrow(unset::validate);
  assertDoesNotThrow(optedIn::validate);
  assertDoesNotThrow(optedOut::validate);
 }

 @Test void massOutOfRangeFailsValidation() {
  var t = new Overrides.Tuning();
  t.massKg = 10.0;
  assertThrows(IllegalArgumentException.class, t::validate);
 }

 @Test void frictionOutOfRangeFailsValidation() {
  var t = new Overrides.Tuning();
  t.friction = 0.0;
  assertThrows(IllegalArgumentException.class, t::validate);
 }

 @Test void missingMassKgIsAllowed() {
  var t = new Overrides.Tuning();
  t.massKg = null;
  assertDoesNotThrow(t::validate);
 }

 // Ticket comment 53181: every tunable field but `enabled` is now a sparse (nullable) Double,
 // and Overrides.resolved() applies the per-field precedence chain (operator override > pack DIV
 // metadata > IV-adapted value > physically-derived value > documented fallback) instead of a
 // caller ever reading a possibly-sparse Tuning directly.

 @Test void resolvedWithNoOverrideEqualsTheDocumentedFallbackForEveryField() {
  var r = Overrides.resolved("packid:not_configured");
  assertEquals(0.3, r.suspensionTravel); assertEquals(1.0, r.friction); assertEquals(8000.0, r.driveForce);
  assertEquals(12000.0, r.brakeForce); assertEquals(30.0, r.maxSteerDegrees);
  assertEquals(0.5, r.hullFillFactor); assertEquals(400.0, r.waterDrag); assertEquals(6000.0, r.propellerForce);
  assertEquals(0.0, r.angularWaterDrag, "angularWaterDrag's fallback is 0 - no rotational damping term unless configured");
  assertEquals(0.0, r.antiRollStiffness, "antiRollStiffness's fallback is 0 - no axle anti-roll coupling unless configured");
  assertEquals(FlightForces.DEFAULT_STALL_ANGLE_DEGREES, r.stallAngleDegrees, "stallAngleDegrees falls back to FlightForces's own previously-hardcoded 15deg");
  assertEquals(FlightForces.DEFAULT_LIFT_SLOPE, r.liftSlope, 1e-9, "liftSlope falls back to FlightForces's own previously-hardcoded 2*pi");
  assertEquals(FlightForces.DEFAULT_CONTROL_SURFACE_SLOPE, r.controlSurfaceSlope, 1e-9, "controlSurfaceSlope falls back to the same previously-hardcoded 2*pi");
  assertEquals(20.0, r.cyclicLimitDegrees, "cyclicLimitDegrees' fallback matches RotorForces' own prior hardcoded 20-degree clamp");
  assertEquals(1.0, r.cyclicAuthorityScale, "cyclicAuthorityScale's fallback is unit scaling - no change from prior behavior");
  assertEquals(1.0, r.yawAuthorityScale, "yawAuthorityScale's fallback is unit scaling - no change from prior behavior");
  assertEquals(0.0, r.rotorAngularDampingCoefficient, "rotorAngularDampingCoefficient's fallback is 0 - no angular-rate damping term at all unless configured (MINECRAFT-537 zero/off parity)");
  assertEquals(0.0, r.rotorAirDragCoefficient, "rotorAirDragCoefficient's fallback is 0 - no translational drag term at all unless configured (MINECRAFT-543 zero/off parity)");
  assertEquals(0.7, r.suspensionDampingRatio, "suspensionDampingRatio's fallback matches PhysicsBridge's own prior hardcoded 0.7 critical-damping multiplier");
  assertEquals(1.0, r.suspensionSpringMultiplier, "suspensionSpringMultiplier's fallback is a pure no-op - an unconfigured vehicle's calibrated spring rate is unchanged");
  assertEquals(1.0, r.longitudinalWaterDragMultiplier, "longitudinalWaterDragMultiplier's fallback is a pure no-op - an unconfigured vehicle's isotropic water drag is unchanged");
  assertEquals(1.0, r.lateralWaterDragMultiplier, "lateralWaterDragMultiplier's fallback is a pure no-op - an unconfigured vehicle's isotropic water drag is unchanged");
  assertEquals(1.0, r.verticalWaterDragMultiplier, "verticalWaterDragMultiplier's fallback is a pure no-op - an unconfigured vehicle's isotropic water drag is unchanged");
  assertEquals(1.0, r.parasiticDragMultiplier, "parasiticDragMultiplier's fallback is a pure no-op - an unconfigured vehicle's native-derived parasitic drag budget is unchanged");
  assertEquals(FlightForces.DEFAULT_OSWALD_EFFICIENCY, r.oswaldEfficiency, "oswaldEfficiency falls back to FlightForces's own previously-hardcoded 0.8");
  assertNull(r.massKg, "massKg has no flat fallback here - PhysicsBridge falls back to the vehicle's own native mass instead");
 }

 @Test void resolvedNeverReturnsANullFieldForThoseWithAFallback() {
  // Every field below the always-populated FALLBACK level must come out non-null regardless of
  // which upstream sources are present - a caller must never see a sparse Tuning for these.
  var r = Overrides.resolved("packid:not_configured", null, null, null);
  assertNotNull(r.suspensionTravel); assertNotNull(r.friction); assertNotNull(r.driveForce);
  assertNotNull(r.brakeForce); assertNotNull(r.maxSteerDegrees);
  assertNotNull(r.hullFillFactor); assertNotNull(r.waterDrag); assertNotNull(r.propellerForce);
  assertNotNull(r.angularWaterDrag);
  assertNotNull(r.antiRollStiffness);
  assertNotNull(r.stallAngleDegrees); assertNotNull(r.liftSlope); assertNotNull(r.controlSurfaceSlope);
  assertNotNull(r.cyclicLimitDegrees); assertNotNull(r.cyclicAuthorityScale); assertNotNull(r.yawAuthorityScale);
  assertNotNull(r.rotorAngularDampingCoefficient);
  assertNotNull(r.rotorAirDragCoefficient);
  assertNotNull(r.suspensionDampingRatio);
  assertNotNull(r.suspensionSpringMultiplier);
  assertNotNull(r.longitudinalWaterDragMultiplier); assertNotNull(r.lateralWaterDragMultiplier); assertNotNull(r.verticalWaterDragMultiplier);
  assertNotNull(r.parasiticDragMultiplier); assertNotNull(r.oswaldEfficiency);
 }

 // MINECRAFT-519: antiRollStiffness - dimensionless 0 (off, the FALLBACK) to 1 (fully-rigid-bar)
 // axle anti-roll coupling strength; see AntiRoll's own doc for the actual redistribution math.

 @Test void antiRollStiffnessOutOfRangeFailsValidation() {
  var negative = new Overrides.Tuning(); negative.antiRollStiffness = -0.1;
  assertThrows(IllegalArgumentException.class, negative::validate);
  var tooLarge = new Overrides.Tuning(); tooLarge.antiRollStiffness = 1.1;
  assertThrows(IllegalArgumentException.class, tooLarge::validate);
  var zero = new Overrides.Tuning(); zero.antiRollStiffness = 0.0; // explicit zero/off is allowed
  assertDoesNotThrow(zero::validate);
  var one = new Overrides.Tuning(); one.antiRollStiffness = 1.0; // fully-rigid-bar upper bound is allowed
  assertDoesNotThrow(one::validate);
  var inRange = new Overrides.Tuning(); inRange.antiRollStiffness = 0.6;
  assertDoesNotThrow(inRange::validate);
 }

 @Test void antiRollStiffnessParticipatesInTheFullPrecedenceChain() {
  try {
   var override = new Overrides.Tuning(); override.antiRollStiffness = 0.35;
   Overrides.setForTest(java.util.Map.of("packid:anti_roll_vehicle", override));
   assertEquals(0.35, Overrides.resolved("packid:anti_roll_vehicle").antiRollStiffness,
     "an explicit operator override must take precedence over the 0.0 fallback");
  } finally {
   Overrides.setForTest(java.util.Map.of());
  }
 }

 // MINECRAFT-522: cyclicLimitDegrees/cyclicAuthorityScale/yawAuthorityScale - bounded cyclic
 // tilt-limit and cyclic/yaw attitude-authority tuning for a rotorcraft.

 @Test void cyclicLimitDegreesOutOfRangeFailsValidation() {
  var zero = new Overrides.Tuning(); zero.cyclicLimitDegrees = 0.0;
  assertThrows(IllegalArgumentException.class, zero::validate);
  var negative = new Overrides.Tuning(); negative.cyclicLimitDegrees = -5.0;
  assertThrows(IllegalArgumentException.class, negative::validate);
  var tooLarge = new Overrides.Tuning(); tooLarge.cyclicLimitDegrees = 45.01;
  assertThrows(IllegalArgumentException.class, tooLarge::validate);
  var atCap = new Overrides.Tuning(); atCap.cyclicLimitDegrees = 45.0;
  assertDoesNotThrow(atCap::validate);
  var inRange = new Overrides.Tuning(); inRange.cyclicLimitDegrees = 15.0;
  assertDoesNotThrow(inRange::validate);
 }

 @Test void cyclicAndYawAuthorityScaleOutOfRangeFailsValidation() {
  var zeroCyclic = new Overrides.Tuning(); zeroCyclic.cyclicAuthorityScale = 0.0;
  assertThrows(IllegalArgumentException.class, zeroCyclic::validate);
  var negativeCyclic = new Overrides.Tuning(); negativeCyclic.cyclicAuthorityScale = -1.0;
  assertThrows(IllegalArgumentException.class, negativeCyclic::validate);
  var tooLargeCyclic = new Overrides.Tuning(); tooLargeCyclic.cyclicAuthorityScale = 5.01;
  assertThrows(IllegalArgumentException.class, tooLargeCyclic::validate);
  var inRangeCyclic = new Overrides.Tuning(); inRangeCyclic.cyclicAuthorityScale = 2.0;
  assertDoesNotThrow(inRangeCyclic::validate);

  var zeroYaw = new Overrides.Tuning(); zeroYaw.yawAuthorityScale = 0.0;
  assertThrows(IllegalArgumentException.class, zeroYaw::validate);
  var tooLargeYaw = new Overrides.Tuning(); tooLargeYaw.yawAuthorityScale = 100.0;
  assertThrows(IllegalArgumentException.class, tooLargeYaw::validate);
  var inRangeYaw = new Overrides.Tuning(); inRangeYaw.yawAuthorityScale = 1.5;
  assertDoesNotThrow(inRangeYaw::validate);
 }

 @Test void cyclicTuningParticipatesInTheFullPrecedenceChain() {
  try {
   var override = new Overrides.Tuning();
   override.cyclicLimitDegrees = 12.0; override.cyclicAuthorityScale = 2.0; override.yawAuthorityScale = 0.5;
   Overrides.setForTest(java.util.Map.of("packid:rotor_vehicle", override));
   var r = Overrides.resolved("packid:rotor_vehicle");
   assertEquals(12.0, r.cyclicLimitDegrees, "an explicit operator override must take precedence over the 20deg fallback");
   assertEquals(2.0, r.cyclicAuthorityScale);
   assertEquals(0.5, r.yawAuthorityScale);
  } finally {
   Overrides.setForTest(java.util.Map.of());
  }
 }

 @Test void antiRollStiffnessIsRejectedOnAPartOverride() {
  var part = new Overrides.Tuning(); part.antiRollStiffness = 0.5;
  assertThrows(IllegalArgumentException.class, part::validateSupportedForPart);
 }

 @Test void cyclicTuningFieldsAreRejectedOnAPartOverride() {
  var limit = new Overrides.Tuning(); limit.cyclicLimitDegrees = 10.0;
  assertThrows(IllegalArgumentException.class, limit::validateSupportedForPart);
  var cyclicScale = new Overrides.Tuning(); cyclicScale.cyclicAuthorityScale = 1.5;
  assertThrows(IllegalArgumentException.class, cyclicScale::validateSupportedForPart);
  var yawScale = new Overrides.Tuning(); yawScale.yawAuthorityScale = 1.5;
  assertThrows(IllegalArgumentException.class, yawScale::validateSupportedForPart);
 }

 // MINECRAFT-537: rotorAngularDampingCoefficient - bounded, optional, rotorcraft-only angular-rate
 // damping torque, independent of the MINECRAFT-522 cyclic/yaw authority fields above.

 @Test void rotorAngularDampingCoefficientOutOfRangeFailsValidation() {
  var negative = new Overrides.Tuning(); negative.rotorAngularDampingCoefficient = -0.1;
  assertThrows(IllegalArgumentException.class, negative::validate);
  var tooLarge = new Overrides.Tuning(); tooLarge.rotorAngularDampingCoefficient = RotorForces.MAX_ANGULAR_DAMPING_COEFFICIENT + 0.01;
  assertThrows(IllegalArgumentException.class, tooLarge::validate);
  var nonFinite = new Overrides.Tuning(); nonFinite.rotorAngularDampingCoefficient = Double.NaN;
  assertThrows(IllegalArgumentException.class, nonFinite::validate);
  var zero = new Overrides.Tuning(); zero.rotorAngularDampingCoefficient = 0.0; // explicit zero/off is allowed
  assertDoesNotThrow(zero::validate);
  var atCap = new Overrides.Tuning(); atCap.rotorAngularDampingCoefficient = RotorForces.MAX_ANGULAR_DAMPING_COEFFICIENT; // the cap itself is allowed
  assertDoesNotThrow(atCap::validate);
  var inRange = new Overrides.Tuning(); inRange.rotorAngularDampingCoefficient = 5.0;
  assertDoesNotThrow(inRange::validate);
 }

 @Test void rotorAngularDampingCoefficientParticipatesInTheFullPrecedenceChain() {
  try {
   var override = new Overrides.Tuning(); override.rotorAngularDampingCoefficient = 4.5;
   Overrides.setForTest(java.util.Map.of("packid:damped_rotor_vehicle", override));
   assertEquals(4.5, Overrides.resolved("packid:damped_rotor_vehicle").rotorAngularDampingCoefficient,
     "an explicit operator override must take precedence over the 0.0 fallback");
  } finally {
   Overrides.setForTest(java.util.Map.of());
  }
 }

 @Test void rotorAngularDampingCoefficientInheritsThroughAnExtendsTemplate() {
  try {
   var template = new Overrides.Tuning(); template.rotorAngularDampingCoefficient = 2.0;
   var child = new Overrides.Tuning(); child.extendsTemplate = "damping-base"; // sets no fields of its own
   Overrides.setForTest(java.util.Map.of("damping-base", template), java.util.Map.of("packid:inherited_damping", child), java.util.Map.of());
   assertEquals(2.0, Overrides.resolved("packid:inherited_damping").rotorAngularDampingCoefficient,
     "a field the child never set must inherit the template's value, same as every other tuning field");
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 @Test void rotorAngularDampingCoefficientIsRejectedOnAPartOverride() {
  var part = new Overrides.Tuning(); part.rotorAngularDampingCoefficient = 1.5;
  assertThrows(IllegalArgumentException.class, part::validateSupportedForPart,
    "this is a vehicle-only concept - a rotorcraft part has no own angular-damping consumer");
 }

 @Test void validThenInvalidRotorAngularDampingReloadAppliesThenRetainsThePriorGoodOverride() {
  // Same "invalid reload retains last-known-good" structure as the part-friction/stall-angle
  // regressions elsewhere in this file - exercises the REAL Overrides.apply(Supplier<Config>)
  // seam, not merely setForTest, so a bad edit mid-session cannot silently corrupt an
  // already-good rotor damping configuration.
  try {
   var goodHelicopter = new Overrides.Tuning(); goodHelicopter.rotorAngularDampingCoefficient = 3.0;
   var goodResult = Overrides.apply(() -> new Overrides.Config(java.util.Map.of(),
     java.util.Map.of("examplepack:reloadable_helicopter", goodHelicopter), java.util.Map.of()));
   assertTrue(goodResult.success(), "a valid rotorAngularDampingCoefficient reload must apply cleanly");
   assertEquals(3.0, Overrides.resolved("examplepack:reloadable_helicopter").rotorAngularDampingCoefficient);

   var invalidHelicopter = new Overrides.Tuning(); invalidHelicopter.rotorAngularDampingCoefficient = -1.0; // out of range
   var badResult = Overrides.apply(() -> new Overrides.Config(java.util.Map.of(),
     java.util.Map.of("examplepack:reloadable_helicopter", invalidHelicopter), java.util.Map.of()));
   assertFalse(badResult.success(), "a negative rotorAngularDampingCoefficient reload must be rejected, not silently applied");
   assertEquals(3.0, Overrides.resolved("examplepack:reloadable_helicopter").rotorAngularDampingCoefficient,
     "a rejected reload must retain the PRIOR GOOD rotorAngularDampingCoefficient, not the invalid value or the 0.0 fallback");
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 // MINECRAFT-543: rotorAirDragCoefficient - bounded, optional, rotorcraft-only translational
 // (linear) air-drag force, independent of rotorAngularDampingCoefficient (MINECRAFT-537, which
 // only ever damps angular rate) and the MINECRAFT-522 cyclic/yaw authority fields above.

 @Test void rotorAirDragCoefficientOutOfRangeFailsValidation() {
  var negative = new Overrides.Tuning(); negative.rotorAirDragCoefficient = -0.1;
  assertThrows(IllegalArgumentException.class, negative::validate);
  var tooLarge = new Overrides.Tuning(); tooLarge.rotorAirDragCoefficient = RotorForces.MAX_AIR_DRAG_COEFFICIENT + 0.01;
  assertThrows(IllegalArgumentException.class, tooLarge::validate);
  var nonFinite = new Overrides.Tuning(); nonFinite.rotorAirDragCoefficient = Double.NaN;
  assertThrows(IllegalArgumentException.class, nonFinite::validate);
  var zero = new Overrides.Tuning(); zero.rotorAirDragCoefficient = 0.0; // explicit zero/off is allowed
  assertDoesNotThrow(zero::validate);
  var atCap = new Overrides.Tuning(); atCap.rotorAirDragCoefficient = RotorForces.MAX_AIR_DRAG_COEFFICIENT; // the cap itself is allowed
  assertDoesNotThrow(atCap::validate);
  var inRange = new Overrides.Tuning(); inRange.rotorAirDragCoefficient = 2.0;
  assertDoesNotThrow(inRange::validate);
 }

 @Test void rotorAirDragCoefficientParticipatesInTheFullPrecedenceChain() {
  try {
   var override = new Overrides.Tuning(); override.rotorAirDragCoefficient = 1.2;
   Overrides.setForTest(java.util.Map.of("packid:drag_rotor_vehicle", override));
   assertEquals(1.2, Overrides.resolved("packid:drag_rotor_vehicle").rotorAirDragCoefficient,
     "an explicit operator override must take precedence over the 0.0 fallback");
  } finally {
   Overrides.setForTest(java.util.Map.of());
  }
 }

 @Test void rotorAirDragCoefficientInheritsThroughAnExtendsTemplate() {
  try {
   var template = new Overrides.Tuning(); template.rotorAirDragCoefficient = 0.8;
   var child = new Overrides.Tuning(); child.extendsTemplate = "air-drag-base"; // sets no fields of its own
   Overrides.setForTest(java.util.Map.of("air-drag-base", template), java.util.Map.of("packid:inherited_air_drag", child), java.util.Map.of());
   assertEquals(0.8, Overrides.resolved("packid:inherited_air_drag").rotorAirDragCoefficient,
     "a field the child never set must inherit the template's value, same as every other tuning field");
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 @Test void rotorAirDragCoefficientIsRejectedOnAPartOverride() {
  var part = new Overrides.Tuning(); part.rotorAirDragCoefficient = 0.5;
  assertThrows(IllegalArgumentException.class, part::validateSupportedForPart,
    "this is a vehicle-only concept - a rotorcraft part has no own translational-drag consumer");
 }

 @Test void validThenInvalidRotorAirDragReloadAppliesThenRetainsThePriorGoodOverride() {
  // Same "invalid reload retains last-known-good" structure as
  // validThenInvalidRotorAngularDampingReloadAppliesThenRetainsThePriorGoodOverride above -
  // exercises the REAL Overrides.apply(Supplier<Config>) seam, not merely setForTest.
  try {
   var goodHelicopter = new Overrides.Tuning(); goodHelicopter.rotorAirDragCoefficient = 0.6;
   var goodResult = Overrides.apply(() -> new Overrides.Config(java.util.Map.of(),
     java.util.Map.of("examplepack:draggy_helicopter", goodHelicopter), java.util.Map.of()));
   assertTrue(goodResult.success(), "a valid rotorAirDragCoefficient reload must apply cleanly");
   assertEquals(0.6, Overrides.resolved("examplepack:draggy_helicopter").rotorAirDragCoefficient);

   var invalidHelicopter = new Overrides.Tuning(); invalidHelicopter.rotorAirDragCoefficient = -1.0; // out of range
   var badResult = Overrides.apply(() -> new Overrides.Config(java.util.Map.of(),
     java.util.Map.of("examplepack:draggy_helicopter", invalidHelicopter), java.util.Map.of()));
   assertFalse(badResult.success(), "a negative rotorAirDragCoefficient reload must be rejected, not silently applied");
   assertEquals(0.6, Overrides.resolved("examplepack:draggy_helicopter").rotorAirDragCoefficient,
     "a rejected reload must retain the PRIOR GOOD rotorAirDragCoefficient, not the invalid value or the 0.0 fallback");
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 // MINECRAFT-518: angularWaterDrag - rotational water-drag torque coefficient for a floating
 // hull, the rotational analogue of waterDrag.

 @Test void angularWaterDragOutOfRangeFailsValidation() {
  var negative = new Overrides.Tuning(); negative.angularWaterDrag = -1.0;
  assertThrows(IllegalArgumentException.class, negative::validate);
  var tooLarge = new Overrides.Tuning(); tooLarge.angularWaterDrag = 100001.0;
  assertThrows(IllegalArgumentException.class, tooLarge::validate);
  var zero = new Overrides.Tuning(); zero.angularWaterDrag = 0.0; // explicit zero is allowed, same as the fallback
  assertDoesNotThrow(zero::validate);
  var inRange = new Overrides.Tuning(); inRange.angularWaterDrag = 250.0;
  assertDoesNotThrow(inRange::validate);
 }

 @Test void angularWaterDragParticipatesInTheFullPrecedenceChain() {
  try {
   var override = new Overrides.Tuning(); override.angularWaterDrag = 123.0;
   Overrides.setForTest(java.util.Map.of("packid:angular_drag_vehicle", override));
   assertEquals(123.0, Overrides.resolved("packid:angular_drag_vehicle").angularWaterDrag,
     "an explicit operator override must take precedence over the 0.0 fallback");
  } finally {
   Overrides.setForTest(java.util.Map.of());
  }
 }

 @Test void angularWaterDragIsRejectedOnAPartOverride() {
  var part = new Overrides.Tuning(); part.angularWaterDrag = 10.0;
  assertThrows(IllegalArgumentException.class, part::validateSupportedForPart);
 }

 // MINECRAFT-521: stallAngleDegrees/liftSlope/controlSurfaceSlope - bounded fixed-wing lift/
 // stall-cap and control-authority tuning, consumed by PhysicsBridge.stepFlight.

 @Test void stallAngleDegreesOutOfRangeFailsValidation() {
  var zero = new Overrides.Tuning(); zero.stallAngleDegrees = 0.0;
  assertThrows(IllegalArgumentException.class, zero::validate);
  var negative = new Overrides.Tuning(); negative.stallAngleDegrees = -5.0;
  assertThrows(IllegalArgumentException.class, negative::validate);
  var tooLarge = new Overrides.Tuning(); tooLarge.stallAngleDegrees = 45.001;
  assertThrows(IllegalArgumentException.class, tooLarge::validate);
  var atCap = new Overrides.Tuning(); atCap.stallAngleDegrees = 45.0;
  assertDoesNotThrow(atCap::validate);
  var inRange = new Overrides.Tuning(); inRange.stallAngleDegrees = 12.0;
  assertDoesNotThrow(inRange::validate);
 }

 @Test void liftSlopeOutOfRangeFailsValidation() {
  var zero = new Overrides.Tuning(); zero.liftSlope = 0.0;
  assertThrows(IllegalArgumentException.class, zero::validate);
  var negative = new Overrides.Tuning(); negative.liftSlope = -1.0;
  assertThrows(IllegalArgumentException.class, negative::validate);
  var tooLarge = new Overrides.Tuning(); tooLarge.liftSlope = 10.001;
  assertThrows(IllegalArgumentException.class, tooLarge::validate);
  var atCap = new Overrides.Tuning(); atCap.liftSlope = 10.0;
  assertDoesNotThrow(atCap::validate);
  var inRange = new Overrides.Tuning(); inRange.liftSlope = 4.0;
  assertDoesNotThrow(inRange::validate);
 }

 @Test void controlSurfaceSlopeOutOfRangeFailsValidation() {
  var zero = new Overrides.Tuning(); zero.controlSurfaceSlope = 0.0;
  assertThrows(IllegalArgumentException.class, zero::validate);
  var negative = new Overrides.Tuning(); negative.controlSurfaceSlope = -1.0;
  assertThrows(IllegalArgumentException.class, negative::validate);
  var tooLarge = new Overrides.Tuning(); tooLarge.controlSurfaceSlope = 10.001;
  assertThrows(IllegalArgumentException.class, tooLarge::validate);
  var atCap = new Overrides.Tuning(); atCap.controlSurfaceSlope = 10.0;
  assertDoesNotThrow(atCap::validate);
  var inRange = new Overrides.Tuning(); inRange.controlSurfaceSlope = 3.0;
  assertDoesNotThrow(inRange::validate);
 }

 @Test void fixedWingFieldsParticipateInTheFullPrecedenceChain() {
  try {
   var override = new Overrides.Tuning();
   override.stallAngleDegrees = 20.0; override.liftSlope = 5.0; override.controlSurfaceSlope = 4.0;
   Overrides.setForTest(java.util.Map.of("packid:glider", override));
   var r = Overrides.resolved("packid:glider");
   assertEquals(20.0, r.stallAngleDegrees, "an explicit operator override must take precedence over the documented fallback");
   assertEquals(5.0, r.liftSlope, 1e-9);
   assertEquals(4.0, r.controlSurfaceSlope, 1e-9);
  } finally {
   Overrides.setForTest(java.util.Map.of());
  }
 }

 @Test void fixedWingFieldsAreRejectedOnAPartOverride() {
  var stall = new Overrides.Tuning(); stall.stallAngleDegrees = 12.0;
  assertThrows(IllegalArgumentException.class, stall::validateSupportedForPart);
  var lift = new Overrides.Tuning(); lift.liftSlope = 4.0;
  assertThrows(IllegalArgumentException.class, lift::validateSupportedForPart);
  var control = new Overrides.Tuning(); control.controlSurfaceSlope = 4.0;
  assertThrows(IllegalArgumentException.class, control::validateSupportedForPart);
 }

 // MINECRAFT-536: parasiticDragMultiplier/oswaldEfficiency - bounded fixed-wing parasitic/
 // induced drag tuning, consumed by PhysicsBridge.stepFlight via FlightForces.dragCoefficient.

 @Test void parasiticDragMultiplierOutOfRangeFailsValidation() {
  var zero = new Overrides.Tuning(); zero.parasiticDragMultiplier = 0.0;
  assertThrows(IllegalArgumentException.class, zero::validate);
  var negative = new Overrides.Tuning(); negative.parasiticDragMultiplier = -1.0;
  assertThrows(IllegalArgumentException.class, negative::validate);
  // Review finding (MINECRAFT-536, PR #31 comment 6901114578): a merely-strictly-positive lower
  // bound let a vanishingly tiny legal value through - 0.1 is the actual documented floor now.
  var belowFloor = new Overrides.Tuning(); belowFloor.parasiticDragMultiplier = 0.0999;
  assertThrows(IllegalArgumentException.class, belowFloor::validate);
  var tinyLegalUnderOldBound = new Overrides.Tuning(); tinyLegalUnderOldBound.parasiticDragMultiplier = Double.MIN_VALUE;
  assertThrows(IllegalArgumentException.class, tinyLegalUnderOldBound::validate, "a vanishingly tiny value must be rejected, not merely 'strictly positive'");
  var tooLarge = new Overrides.Tuning(); tooLarge.parasiticDragMultiplier = 5.001;
  assertThrows(IllegalArgumentException.class, tooLarge::validate);
  var atFloor = new Overrides.Tuning(); atFloor.parasiticDragMultiplier = 0.1;
  assertDoesNotThrow(atFloor::validate);
  var atCap = new Overrides.Tuning(); atCap.parasiticDragMultiplier = 5.0;
  assertDoesNotThrow(atCap::validate);
  var inRange = new Overrides.Tuning(); inRange.parasiticDragMultiplier = 1.3;
  assertDoesNotThrow(inRange::validate);
 }

 @Test void oswaldEfficiencyOutOfRangeFailsValidation() {
  var zero = new Overrides.Tuning(); zero.oswaldEfficiency = 0.0;
  assertThrows(IllegalArgumentException.class, zero::validate);
  var negative = new Overrides.Tuning(); negative.oswaldEfficiency = -0.5;
  assertThrows(IllegalArgumentException.class, negative::validate);
  // Review finding (MINECRAFT-536, PR #31 comment 6901114578): Overrides.Tuning.validate
  // previously accepted every finite oswaldEfficiency > 0, while FlightForces.dragCoefficient
  // divides the induced term by it - a legal tiny value such as Double.MIN_VALUE underflows
  // Math.PI*aspectRatio*efficiency to zero for a normal fixed wing, yielding Infinity drag/force
  // with nonzero lift. 0.5 is the actual documented physical floor now, below every real
  // conventional wing's Oswald efficiency.
  var belowFloor = new Overrides.Tuning(); belowFloor.oswaldEfficiency = 0.4999;
  assertThrows(IllegalArgumentException.class, belowFloor::validate);
  var tinyLegalUnderOldBound = new Overrides.Tuning(); tinyLegalUnderOldBound.oswaldEfficiency = Double.MIN_VALUE;
  assertThrows(IllegalArgumentException.class, tinyLegalUnderOldBound::validate,
    "a vanishingly tiny value must be rejected, not merely 'strictly positive' - it would drive the induced term's denominator to a vanishing subnormal, overflowing the division to Infinity drag (confirmed directly against FlightForces.dragCoefficient below)");
  var tooLarge = new Overrides.Tuning(); tooLarge.oswaldEfficiency = 1.001;
  assertThrows(IllegalArgumentException.class, tooLarge::validate);
  var atFloor = new Overrides.Tuning(); atFloor.oswaldEfficiency = 0.5;
  assertDoesNotThrow(atFloor::validate);
  var atCap = new Overrides.Tuning(); atCap.oswaldEfficiency = 1.0;
  assertDoesNotThrow(atCap::validate);
  var inRange = new Overrides.Tuning(); inRange.oswaldEfficiency = 0.75;
  assertDoesNotThrow(inRange::validate);
 }

 @Test void tinyLegalOswaldEfficiencyWouldHaveProducedInfiniteDragBeforeThisBoundFix() {
  // Review finding (MINECRAFT-536, PR #31 comment 6901114578): demonstrates the actual numeric
  // hazard the floor above closes - directly against FlightForces.dragCoefficient (the real
  // production call site), not just Overrides.validate() in isolation. A normal fixed-wing
  // aspect ratio (8) with Double.MIN_VALUE as the efficiency underflows the induced term's
  // denominator to exactly 0.0, producing Infinity - confirming this was a real, not theoretical,
  // finite-result hazard prior to the validated floor.
  double hazardResult = FlightForces.dragCoefficient(1.0, 0.03, 8, 1.0, Double.MIN_VALUE);
  assertTrue(Double.isInfinite(hazardResult), "confirms the exact hazard this ticket's validated oswaldEfficiency floor (0.5) now prevents from ever reaching this method in production");
 }

 @Test void dragTuningFieldsParticipateInTheFullPrecedenceChain() {
  try {
   var override = new Overrides.Tuning();
   override.parasiticDragMultiplier = 1.4; override.oswaldEfficiency = 0.7;
   Overrides.setForTest(java.util.Map.of("packid:glider3", override));
   var r = Overrides.resolved("packid:glider3");
   assertEquals(1.4, r.parasiticDragMultiplier, "an explicit operator override must take precedence over the documented fallback");
   assertEquals(0.7, r.oswaldEfficiency, 1e-9);
  } finally {
   Overrides.setForTest(java.util.Map.of());
  }
 }

 @Test void dragTuningFieldsAreRejectedOnAPartOverride() {
  var multiplier = new Overrides.Tuning(); multiplier.parasiticDragMultiplier = 1.3;
  assertThrows(IllegalArgumentException.class, multiplier::validateSupportedForPart);
  var efficiency = new Overrides.Tuning(); efficiency.oswaldEfficiency = 0.7;
  assertThrows(IllegalArgumentException.class, efficiency::validateSupportedForPart);
 }

 @Test void brakeBiasFrontOutOfRangeFailsValidation() {
  var negative = new Overrides.Tuning(); negative.brakeBiasFront = -0.01;
  assertThrows(IllegalArgumentException.class, negative::validate);
  var tooLarge = new Overrides.Tuning(); tooLarge.brakeBiasFront = 1.01;
  assertThrows(IllegalArgumentException.class, tooLarge::validate);
  var allRear = new Overrides.Tuning(); allRear.brakeBiasFront = 0.0; // inclusive extreme, unlike lateralGripMultiplier
  assertDoesNotThrow(allRear::validate);
  var allFront = new Overrides.Tuning(); allFront.brakeBiasFront = 1.0;
  assertDoesNotThrow(allFront::validate);
  var inRange = new Overrides.Tuning(); inRange.brakeBiasFront = 0.65;
  assertDoesNotThrow(inRange::validate);
 }

 @Test void brakeBiasFrontHasNoFallbackAndStaysNullWhenNeverConfigured() {
  // Unlike brakeForce/friction/etc., brakeBiasFront must stay null when nothing at any
  // precedence level sets it - PhysicsBridge/BrakeBiasDistribution rely on literal null (not a
  // 0.5 fallback) to mean "no operator bias at all" (the exact pre-ticket behavior).
  assertNull(Overrides.resolved("packid:not_configured").brakeBiasFront);
  assertNull(Overrides.defaultTuning().brakeBiasFront);
 }

 @Test void brakeBiasFrontParticipatesInTheFullPrecedenceChain() {
  try {
   var override = new Overrides.Tuning(); override.brakeBiasFront = 0.65;
   Overrides.setForTest(java.util.Map.of("packid:brake_bias_vehicle", override));
   assertEquals(0.65, Overrides.resolved("packid:brake_bias_vehicle").brakeBiasFront,
     "an explicit operator override must be used instead of staying null");
  } finally {
   Overrides.setForTest(java.util.Map.of());
  }
  var packMetadata = new Overrides.Tuning(); packMetadata.brakeBiasFront = 0.4;
  assertEquals(0.4, Overrides.resolved("packid:not_configured", packMetadata, null, null).brakeBiasFront,
    "with no operator override, pack DIV metadata must still be used over staying null");
 }

 @Test void brakeBiasFrontIsRejectedOnAPartOverride() {
  var part = new Overrides.Tuning(); part.brakeBiasFront = 0.5;
  assertThrows(IllegalArgumentException.class, part::validateSupportedForPart);
 }

 @Test void driveBiasFrontOutOfRangeFailsValidation() {
  var negative = new Overrides.Tuning(); negative.driveBiasFront = -0.01;
  assertThrows(IllegalArgumentException.class, negative::validate);
  var tooLarge = new Overrides.Tuning(); tooLarge.driveBiasFront = 1.01;
  assertThrows(IllegalArgumentException.class, tooLarge::validate);
  var allRear = new Overrides.Tuning(); allRear.driveBiasFront = 0.0; // inclusive extreme, unlike lateralGripMultiplier
  assertDoesNotThrow(allRear::validate);
  var allFront = new Overrides.Tuning(); allFront.driveBiasFront = 1.0;
  assertDoesNotThrow(allFront::validate);
  var inRange = new Overrides.Tuning(); inRange.driveBiasFront = 0.65;
  assertDoesNotThrow(inRange::validate);
 }

 @Test void driveBiasFrontHasNoFallbackAndStaysNullWhenNeverConfigured() {
  // Unlike driveForce/friction/etc., driveBiasFront must stay null when nothing at any
  // precedence level sets it - PhysicsBridge/DriveBiasDistribution rely on literal null (not a
  // 0.5 fallback) to mean "no operator bias at all" (the exact pre-ticket behavior).
  assertNull(Overrides.resolved("packid:not_configured").driveBiasFront);
  assertNull(Overrides.defaultTuning().driveBiasFront);
 }

 @Test void driveBiasFrontParticipatesInTheFullPrecedenceChain() {
  try {
   var override = new Overrides.Tuning(); override.driveBiasFront = 0.65;
   Overrides.setForTest(java.util.Map.of("packid:drive_bias_vehicle", override));
   assertEquals(0.65, Overrides.resolved("packid:drive_bias_vehicle").driveBiasFront,
     "an explicit operator override must be used instead of staying null");
  } finally {
   Overrides.setForTest(java.util.Map.of());
  }
  var packMetadata = new Overrides.Tuning(); packMetadata.driveBiasFront = 0.4;
  assertEquals(0.4, Overrides.resolved("packid:not_configured", packMetadata, null, null).driveBiasFront,
    "with no operator override, pack DIV metadata must still be used over staying null");
 }

 @Test void driveBiasFrontIsRejectedOnAPartOverride() {
  var part = new Overrides.Tuning(); part.driveBiasFront = 0.5;
  assertThrows(IllegalArgumentException.class, part::validateSupportedForPart);
 }

 @Test void axleDriveForceSharingHasNoFallbackAndStaysNullWhenNeverConfigured() {
  // Same "stays null, not a default value" shape as driveBiasFront above -
  // AxleDriveForceSharing's own caller treats null as "disabled", not a fabricated default.
  assertNull(Overrides.resolved("packid:not_configured").axleDriveForceSharing);
  assertNull(Overrides.defaultTuning().axleDriveForceSharing);
 }

 @Test void axleDriveForceSharingParticipatesInTheFullPrecedenceChain() {
  try {
   var override = new Overrides.Tuning(); override.axleDriveForceSharing = true;
   Overrides.setForTest(java.util.Map.of("packid:axle_sharing_vehicle", override));
   assertEquals(true, Overrides.resolved("packid:axle_sharing_vehicle").axleDriveForceSharing,
     "an explicit operator override must be used instead of staying null");
  } finally {
   Overrides.setForTest(java.util.Map.of());
  }
  var packMetadata = new Overrides.Tuning(); packMetadata.axleDriveForceSharing = true;
  assertEquals(true, Overrides.resolved("packid:not_configured", packMetadata, null, null).axleDriveForceSharing,
    "with no operator override, pack DIV metadata must still be used over staying null");
 }

 @Test void axleDriveForceSharingIsRejectedOnAPartOverride() {
  var part = new Overrides.Tuning(); part.axleDriveForceSharing = true;
  assertThrows(IllegalArgumentException.class, part::validateSupportedForPart);
 }

 @Test void packMetadataFillsAGapBelowAMissingOperatorOverrideButAboveTheFallback() {
  var packMetadata = new Overrides.Tuning();
  packMetadata.suspensionTravel = 0.45;
  var r = Overrides.resolved("packid:not_configured", packMetadata, null, null);
  assertEquals(0.45, r.suspensionTravel, "with no operator override, pack DIV metadata must win over the documented fallback");
  assertEquals(1.0, r.friction, "a field pack metadata doesn't mention must still fall through to the documented fallback");
 }

 @Test void ivAdaptedValueFillsAGapBelowBothOperatorOverrideAndPackMetadata() {
  var ivAdapted = new Overrides.Tuning();
  ivAdapted.friction = 0.85;
  var r = Overrides.resolved("packid:not_configured", null, ivAdapted, null);
  assertEquals(0.85, r.friction, "with no operator override or pack metadata, an IV-adapted native value must win over the documented fallback");
 }

 @Test void physicallyDerivedValueFillsAGapBelowOperatorOverridePackMetadataAndIvAdapted() {
  var physicallyDerived = new Overrides.Tuning();
  physicallyDerived.friction = 0.72;
  var r = Overrides.resolved("packid:not_configured", null, null, physicallyDerived);
  assertEquals(0.72, r.friction, "with nothing above it, a physically-derived value must win over the documented fallback");
 }

 @Test void precedenceOrderIsOperatorOverrideThenPackMetadataThenIvAdaptedThenPhysicallyDerivedThenFallback() {
  var packMetadata = new Overrides.Tuning(); packMetadata.friction = 0.5;
  var ivAdapted = new Overrides.Tuning(); ivAdapted.friction = 0.6;
  var physicallyDerived = new Overrides.Tuning(); physicallyDerived.friction = 0.65;
  // No operator override entry exists for this key, so pack metadata must win over the (also
  // present) lower-precedence sources - precedence, not "whichever is supplied".
  var r = Overrides.resolved("packid:not_configured", packMetadata, ivAdapted, physicallyDerived);
  assertEquals(0.5, r.friction, "pack DIV metadata must take precedence over lower-precedence sources when all are present");
 }

 @Test void enabledAndMassKgParticipateInTheFullPrecedenceChainNotJustOperatorOverride() {
  // Comment 53184: an earlier version of resolved() checked ONLY the operator override for
  // enabled/massKg, ignoring pack metadata and IV-adapted entirely.
  var packMetadata = new Overrides.Tuning(); packMetadata.enabled = false; packMetadata.massKg = 420.0;
  var r = Overrides.resolved("packid:not_configured", packMetadata, null, null);
  assertEquals(false, r.enabled, "pack metadata's enabled must be used when no operator override sets it");
  assertEquals(420.0, r.massKg, "pack metadata's massKg must be used when no operator override sets it");
 }

 @Test void malformedPackMetadataOrIvAdaptedFailsLoudlyInsteadOfBeingSilentlyTrusted() {
  var badPackMetadata = new Overrides.Tuning(); badPackMetadata.friction = -1.0;
  assertThrows(IllegalArgumentException.class, () -> Overrides.resolved("packid:not_configured", badPackMetadata, null, null));
  var badIvAdapted = new Overrides.Tuning(); badIvAdapted.driveForce = -500.0;
  assertThrows(IllegalArgumentException.class, () -> Overrides.resolved("packid:not_configured", null, badIvAdapted, null));
 }

 @Test void explicitOperatorOverrideEntryTakesPrecedenceOverEverythingElseIncludingPartialFields() {
  var explicit = new Overrides.Tuning();
  explicit.driveForce = 9999.0; // only one field explicitly set - the rest of this entry is sparse
  Overrides.setForTest(java.util.Map.of("packid:explicit_vehicle", explicit));
  try {
   var packMetadata = new Overrides.Tuning(); packMetadata.driveForce = 1234.0; packMetadata.friction = 0.9;
   var r = Overrides.resolved("packid:explicit_vehicle", packMetadata, null, null);
   assertEquals(9999.0, r.driveForce, "an explicit operator override must win over pack metadata for the field it actually sets");
   assertEquals(0.9, r.friction, "a field the explicit entry did NOT set must still fall through to pack metadata, not silently use the documented fallback instead");
   assertEquals(0.3, Overrides.resolved("packid:unrelated_vehicle", null, null, null).suspensionTravel,
     "an override entry for one key must never leak into another vehicle's resolution");
  } finally {
   Overrides.setForTest(java.util.Map.of()); // restore isolation for other tests
  }
 }

 // Ticket comment 53182/53188: selfRightingConfig is all-or-nothing - every one of its five
 // fields must be present together, or the assist stays OFF.

 @Test void selfRightingConfigIsOffWhenNoFieldsAreSet() {
  var config = Overrides.selfRightingConfig(Overrides.resolved("packid:not_configured"));
  assertEquals(0, config.strength()); assertEquals(0, config.damping());
 }

 @Test void selfRightingConfigIsOffWhenOnlySomeFieldsAreSet() {
  var t = Overrides.resolved("packid:not_configured");
  t.selfRightStrength = 200.0; t.selfRightDamping = 80.0; // maxTorque/fade fields left unset
  var config = Overrides.selfRightingConfig(t);
  assertEquals(0, config.strength(), "a partially-configured assist must stay OFF, not silently activate with some fields missing");
 }

 @Test void selfRightingConfigActivatesOnlyWhenAllFiveFieldsAreSet() {
  var t = Overrides.resolved("packid:not_configured");
  t.selfRightStrength = 200.0; t.selfRightDamping = 80.0; t.selfRightMaxTorque = 600.0;
  t.selfRightFadeStartDegrees = 10.0; t.selfRightFadeEndDegrees = 50.0;
  var config = Overrides.selfRightingConfig(t);
  assertEquals(200.0, config.strength()); assertEquals(80.0, config.damping()); assertEquals(600.0, config.maxTorque());
  assertEquals(10.0, config.fadeStartDegrees()); assertEquals(50.0, config.fadeEndDegrees());
 }

 @Test void explicitQuadExampleShapeValidatesAsAWorkingSelfRightConfig() {
  // Mirrors the real seed Overrides.load() writes on a fresh install for mtsofficialpack:quad
  // (ticket comment 53182/53188's "explicit quad roll-assist" ask) - not reading the actual
  // seeded file (that needs live FML config I/O), just confirming this exact shape of entry
  // produces a working, validating, active config.
  var quadExample = new Overrides.Tuning();
  quadExample.selfRightStrength=200.0; quadExample.selfRightDamping=80.0; quadExample.selfRightMaxTorque=600.0;
  quadExample.selfRightFadeStartDegrees=10.0; quadExample.selfRightFadeEndDegrees=50.0;
  assertDoesNotThrow(quadExample::validate);
  var config = Overrides.selfRightingConfig(quadExample);
  assertTrue(config.strength()>0 && config.damping()>0, "the quad example entry must resolve to an ACTIVE self-right config, not OFF");
 }

 @Test void selfRightFieldsOutOfRangeFailValidation() {
  var negativeStrength = new Overrides.Tuning(); negativeStrength.selfRightStrength = -1.0;
  assertThrows(IllegalArgumentException.class, negativeStrength::validate);
  var fadeEndBeforeStart = new Overrides.Tuning(); fadeEndBeforeStart.selfRightFadeStartDegrees = 50.0; fadeEndBeforeStart.selfRightFadeEndDegrees = 10.0;
  assertThrows(IllegalArgumentException.class, fadeEndBeforeStart::validate);
  var fadeEndBeyondHardCap = new Overrides.Tuning(); fadeEndBeyondHardCap.selfRightFadeStartDegrees = 10.0;
  fadeEndBeyondHardCap.selfRightFadeEndDegrees = io.github.brooswitminecraft.div.experimental.SelfRightingAssist.HARD_SAFETY_CAP_DEGREES + 1;
  assertThrows(IllegalArgumentException.class, fadeEndBeyondHardCap::validate);
 }

 // Two-half chassis configuration plumbing (ticket comment 53236/53240).

 @Test void upperMassFractionFallsBackToAnEvenSplitWhenUnconfigured() {
  assertEquals(0.5, Overrides.resolved("packid:not_configured").upperMassFraction);
 }

 @Test void upperMassFractionOutOfRangeFailsValidation() {
  var zero = new Overrides.Tuning(); zero.upperMassFraction = 0.0;
  assertThrows(IllegalArgumentException.class, zero::validate);
  var one = new Overrides.Tuning(); one.upperMassFraction = 1.0;
  assertThrows(IllegalArgumentException.class, one::validate);
  var negative = new Overrides.Tuning(); negative.upperMassFraction = -0.1;
  assertThrows(IllegalArgumentException.class, negative::validate);
  var nan = new Overrides.Tuning(); nan.upperMassFraction = Double.NaN;
  assertThrows(IllegalArgumentException.class, nan::validate);
  var inRange = new Overrides.Tuning(); inRange.upperMassFraction = 0.4;
  assertDoesNotThrow(inRange::validate);
 }

 @Test void upperMassFractionParticipatesInTheFullPrecedenceChain() {
  try {
   var override = new Overrides.Tuning(); override.upperMassFraction = 0.6;
   Overrides.setForTest(java.util.Map.of("packid:two_body_vehicle", override));
   assertEquals(0.6, Overrides.resolved("packid:two_body_vehicle").upperMassFraction,
     "an explicit operator override must take precedence over the 0.5 fallback");
  } finally {
   Overrides.setForTest(java.util.Map.of());
  }
 }

 @Test void twoBodyConfigBuildsFromTheResolvedFractionAndMatchesItExactly() {
  var resolved = Overrides.resolved("packid:not_configured");
  var config = Overrides.twoBodyConfig(resolved, 350.0);
  assertEquals(0.5, config.upperMassFraction());
 }

 @Test void twoBodyConfigValidatesAgainstTheRealTotalMassNotJustTheFractionInIsolation() {
  var override = new Overrides.Tuning(); override.upperMassFraction = 0.001; // a perfectly in-range fraction...
  try {
   Overrides.setForTest(java.util.Map.of("packid:extreme_fraction", override));
   var resolved = Overrides.resolved("packid:extreme_fraction");
   assertThrows(IllegalArgumentException.class, () -> Overrides.twoBodyConfig(resolved, 100.0),
     "...that is still unsafe for a light vehicle's real total mass - must fail here, not silently produce a near-massless half");
   assertDoesNotThrow(() -> Overrides.twoBodyConfig(resolved, 1_000_000.0),
     "the SAME fraction must be accepted for a heavy enough vehicle where neither half falls below the minimum");
  } finally {
   Overrides.setForTest(java.util.Map.of());
  }
 }

 // Ticket 53518 (MINECRAFT-505): layered JSON vehicle/part overrides - `parts`/`templates`
 // namespaces, `extends` inheritance, and non-throwing reload() with last-known-good semantics.
 // These all go through the package-private three-namespace setForTest seam so they run as a
 // plain unit test, the same reasoning OverridesTest's class doc already gives for `load()`.

 @Test void partOverrideResolvesIndependentlyOfAnyVehicleOverride() {
  try {
   var part = new Overrides.Tuning(); part.friction = 0.42;
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of("packid:engine_part", part));
   assertEquals(0.42, Overrides.resolvedPart("packid:engine_part").friction);
   assertEquals(1.0, Overrides.resolved("packid:engine_part").friction,
     "a part override must never leak into vehicle-key resolution for the same string, and vice versa - separate namespaces");
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 @Test void partOverrideFallsBackToTheSameDocumentedFallbackAsVehiclesWhenUnconfigured() {
  assertEquals(0.3, Overrides.resolvedPart("packid:not_configured_part").suspensionTravel);
  assertEquals(8000.0, Overrides.resolvedPart("packid:not_configured_part").driveForce);
 }

 @Test void extendsInheritsEveryUnsetFieldFromItsTemplate() {
  try {
   var template = new Overrides.Tuning(); template.driveForce = 5000.0; template.friction = 0.8;
   var child = new Overrides.Tuning(); child.extendsTemplate = "base"; child.friction = 0.95; // overrides the template's own friction
   Overrides.setForTest(java.util.Map.of("base", template), java.util.Map.of("packid:child_vehicle", child), java.util.Map.of());
   var r = Overrides.resolved("packid:child_vehicle");
   assertEquals(5000.0, r.driveForce, "a field the child never set must inherit the template's value");
   assertEquals(0.95, r.friction, "a field the child DID set must win over the template's own value");
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 @Test void extendsChainsThroughMultipleTemplates() {
  try {
   var grandparent = new Overrides.Tuning(); grandparent.brakeForce = 9000.0;
   var parent = new Overrides.Tuning(); parent.extendsTemplate = "grandparent"; parent.driveForce = 4000.0;
   var child = new Overrides.Tuning(); child.extendsTemplate = "parent";
   Overrides.setForTest(java.util.Map.of("grandparent", grandparent, "parent", parent),
     java.util.Map.of("packid:chained_vehicle", child), java.util.Map.of());
   var r = Overrides.resolved("packid:chained_vehicle");
   assertEquals(9000.0, r.brakeForce, "a field set only on the grandparent template must still reach the child");
   assertEquals(4000.0, r.driveForce);
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 @Test void extendsUnknownTemplateFailsLoudlyInsteadOfSilentlyIgnoringIt() {
  try {
   var child = new Overrides.Tuning(); child.extendsTemplate = "does_not_exist";
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of("packid:broken_vehicle", child), java.util.Map.of());
   assertThrows(IllegalArgumentException.class, () -> Overrides.resolved("packid:broken_vehicle"));
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 @Test void extendsCycleFailsLoudlyInsteadOfInfiniteLooping() {
  try {
   var a = new Overrides.Tuning(); a.extendsTemplate = "b";
   var b = new Overrides.Tuning(); b.extendsTemplate = "a";
   Overrides.setForTest(java.util.Map.of("a", a, "b", b), java.util.Map.of("packid:cyclic_vehicle", a), java.util.Map.of());
   assertThrows(IllegalArgumentException.class, () -> Overrides.resolved("packid:cyclic_vehicle"));
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 @Test void extendsResolvesAgainstTemplatesNamespaceOnlyNotVehiclesOrParts() {
  // A template name happening to collide with an unrelated vehicle's own key must not make that
  // vehicle entry a valid (and confusing) extension target - templates are their own namespace.
  try {
   var unrelatedVehicle = new Overrides.Tuning(); unrelatedVehicle.driveForce = 1.0;
   var child = new Overrides.Tuning(); child.extendsTemplate = "packid:unrelated_vehicle";
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of("packid:unrelated_vehicle", unrelatedVehicle, "packid:child", child), java.util.Map.of());
   assertThrows(IllegalArgumentException.class, () -> Overrides.resolved("packid:child"),
     "a vehicle key is not a valid extends target even though a template with that exact name does not exist");
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 @Test void resolvingTwoDifferentKeysProducesIndependentMutableTuningInstances() {
  // "Multi-instance isolation" (ticket 53518): two different vehicle instances of two different
  // (or even the same) definition must never share one mutable Tuning object - each resolved()
  // call is its own fresh snapshot.
  var first = Overrides.resolved("packid:not_configured");
  var second = Overrides.resolved("packid:not_configured");
  assertNotSame(first, second);
  first.driveForce = 1.0;
  assertEquals(8000.0, second.driveForce, "mutating one resolved() instance's own copy must never affect a separately resolved instance for the same key");
 }

 // Review findings (John's comment 53539): isOptedOut must honor an `enabled:false` inherited
 // through `extends`, and a merged (effective) Tuning - not just each raw entry - must itself be
 // validated so a coupled check spanning a child/template boundary cannot slip through.

 @Test void isOptedOutHonorsEnabledInheritedThroughExtends() {
  try {
   var disabledTemplate = new Overrides.Tuning(); disabledTemplate.enabled = false;
   var child = new Overrides.Tuning(); child.extendsTemplate = "disabled-template"; // sets no fields of its own
   Overrides.setForTest(java.util.Map.of("disabled-template", disabledTemplate), java.util.Map.of("packid:inherited_opt_out", child), java.util.Map.of());
   assertTrue(Overrides.isOptedOut("packid:inherited_opt_out"),
     "enabled:false inherited from a template must opt this vehicle out, the same as resolved() already reports it");
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 @Test void isOptedOutStillWorksForADirectEntryWithNoExtends() {
  try {
   var direct = new Overrides.Tuning(); direct.enabled = false;
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of("packid:directly_opted_out", direct), java.util.Map.of());
   assertTrue(Overrides.isOptedOut("packid:directly_opted_out"));
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 @Test void mergedEffectiveTuningCanBeInvalidEvenWhenEveryRawEntryValidatesOnItsOwn() {
  // The exact gap readAndValidate's post-merge validate() (not just each raw entry's own
  // validate()) now closes: a template sets ONLY fadeStartDegrees=40 (valid alone - fadeEndDegrees
  // null means the coupled check's own null-guard passes trivially), a child extending it sets
  // ONLY fadeEndDegrees=10 (also valid alone, compared against the raw child's own null
  // fadeStartDegrees as "> 0"). Neither raw entry, validated alone, ever sees both fields
  // together - but the MERGED result (fadeStartDegrees=40, fadeEndDegrees=10) violates
  // "fadeEnd > fadeStart" and must fail once actually merged and validated.
  try {
   var template = new Overrides.Tuning(); template.selfRightFadeStartDegrees = 40.0;
   var child = new Overrides.Tuning(); child.extendsTemplate = "base"; child.selfRightFadeEndDegrees = 10.0;
   Overrides.setForTest(java.util.Map.of("base", template), java.util.Map.of(), java.util.Map.of());
   assertDoesNotThrow(template::validate, "the raw template alone is valid");
   assertDoesNotThrow(child::validate, "the raw child alone is valid (its own fadeStartDegrees is null)");
   assertThrows(IllegalArgumentException.class, () -> Overrides.effective(child).validate(),
     "the MERGED combination is invalid and must be caught once both fields are actually together");
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 // Ticket 53518 self-review / John's comment 53539: a part override must actually reach a real
 // physics consumer, not just resolve to a value nothing reads. PhysicsBridge.enginePartDriveForce
 // is the literal decision stepGround's drive-force computation now uses.

 @Test void effectivePartOverrideIsNullForAnUnconfiguredPartUnlikeResolvedPartsFallbackFilledValue() {
  assertNull(Overrides.effectivePartOverride("packid:not_configured_part"),
    "effectivePartOverride must distinguish \"no override\" from resolvedPart's own flat fallback");
  assertEquals(8000.0, Overrides.resolvedPart("packid:not_configured_part").driveForce,
    "resolvedPart itself is unaffected - still falls through to the same flat FALLBACK as a vehicle");
 }

 @Test void effectivePartOverrideAppliesExtendsTheSameWayVehicleResolutionDoes() {
  try {
   var template = new Overrides.Tuning(); template.driveForce = 7000.0;
   var part = new Overrides.Tuning(); part.extendsTemplate = "engine-base";
   Overrides.setForTest(java.util.Map.of("engine-base", template), java.util.Map.of(), java.util.Map.of("packid:inherited_engine", part));
   assertEquals(7000.0, Overrides.effectivePartOverride("packid:inherited_engine").driveForce);
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 @Test void enginePartDriveForceOverrideWinsOverTheVehicleResolvedValue() {
  try {
   var part = new Overrides.Tuning(); part.driveForce = 9500.0;
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of("examplepack:high_output_engine", part));
   assertEquals(9500.0, PhysicsBridge.enginePartDriveForce("examplepack:high_output_engine", 8000.0),
     "an explicit part-level driveForce override must win outright over the vehicle's own resolved value");
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 @Test void enginePartDriveForceFallsThroughToTheVehicleResolvedValueWhenNoPartOverrideExists() {
  assertEquals(8000.0, PhysicsBridge.enginePartDriveForce("packid:ordinary_engine_with_no_override", 8000.0));
 }

 // Review finding (self-review 53556/PR comment 6854012663): "narrow the accepted part schema"
 // - a parts entry may only set driveForce or friction (MINECRAFT-529) today; every other field
 // is rejected at load/reload, including one reached only through extends, not silently
 // accepted-but-ignored.

 @Test void partEntrySettingOnlyDriveForcePassesTheSupportedFieldsCheck() {
  var part = new Overrides.Tuning(); part.driveForce = 9500.0;
  assertDoesNotThrow(part::validateSupportedForPart);
 }

 @Test void partEntrySettingAnUnsupportedFieldFailsTheSupportedFieldsCheck() {
  var brakeOverride = new Overrides.Tuning(); brakeOverride.brakeForce = 1000.0;
  assertThrows(IllegalArgumentException.class, brakeOverride::validateSupportedForPart);
  var selfRightOverride = new Overrides.Tuning(); selfRightOverride.selfRightStrength = 1.0;
  assertThrows(IllegalArgumentException.class, selfRightOverride::validateSupportedForPart);
  var massOverride = new Overrides.Tuning(); massOverride.massKg = 500.0;
  assertThrows(IllegalArgumentException.class, massOverride::validateSupportedForPart);
 }

 // MINECRAFT-529: installed tire-specific grip overrides - friction is now a supported part
 // field (removed from validateSupportedForPart's reject list), with the exact same bounds
 // validate() already enforces for a vehicle-level friction (friction>0 && friction<=3), and the
 // same extends-inheritance/precedence machinery every other part field already gets.

 @Test void partEntrySettingFrictionPassesTheSupportedFieldsCheck() {
  var part = new Overrides.Tuning(); part.friction = 1.4;
  assertDoesNotThrow(part::validateSupportedForPart,
    "MINECRAFT-529: an installed tire part's own friction override must be an accepted part field");
 }

 @Test void partEntrySettingOutOfRangeFrictionStillFailsPlainValidation() {
  // validateSupportedForPart only checks WHICH fields are set, never bounds - validate() is the
  // bounds check, and it is run (readAndValidate/validateConfig) before validateSupportedForPart
  // ever sees the merged entry, so an out-of-range part-level friction still fails loudly.
  var tooHigh = new Overrides.Tuning(); tooHigh.friction = 3.5;
  assertThrows(IllegalArgumentException.class, tooHigh::validate);
  var tooLow = new Overrides.Tuning(); tooLow.friction = 0.0;
  assertThrows(IllegalArgumentException.class, tooLow::validate);
 }

 @Test void partFrictionOverrideInheritsThroughAnExtendsTemplate() {
  try {
   var template = new Overrides.Tuning(); template.friction = 2.1;
   var part = new Overrides.Tuning(); part.extendsTemplate = "tire-base";
   Overrides.setForTest(java.util.Map.of("tire-base", template), java.util.Map.of(), java.util.Map.of("packid:inherited_tire", part));
   var merged = Overrides.effectivePartOverride("packid:inherited_tire");
   assertEquals(2.1, merged.friction, "a part inheriting friction from a template must fold it in exactly like any other field");
   assertDoesNotThrow(merged::validateSupportedForPart);
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 @Test void tirePartFrictionOverrideWinsOverTheVehicleResolvedValue() {
  try {
   var part = new Overrides.Tuning(); part.friction = 1.8;
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of("examplepack:grippy_tire", part));
   assertEquals(1.8, PhysicsBridge.tirePartFriction("examplepack:grippy_tire", 1.0),
     "an explicit part-level friction override must win outright (replace, not multiply) over the vehicle's own resolved friction");
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 @Test void tirePartFrictionFallsThroughToTheVehicleResolvedValueWhenNoPartOverrideExists() {
  assertEquals(1.0, PhysicsBridge.tirePartFriction("packid:ordinary_tire_with_no_override", 1.0),
    "no parts entry at all for this tire key must be a true no-op, reproducing the vehicle's resolved friction unchanged");
 }

 @Test void tirePartFrictionResolvesIndependentlyPerWheelKeySoDifferentInstalledTiresGetDifferentGrip() {
  try {
   var grippy = new Overrides.Tuning(); grippy.friction = 2.0;
   var slick = new Overrides.Tuning(); slick.friction = 0.4;
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(),
     java.util.Map.of("examplepack:grippy_tire", grippy, "examplepack:slick_tire", slick));
   assertEquals(2.0, PhysicsBridge.tirePartFriction("examplepack:grippy_tire", 1.0));
   assertEquals(0.4, PhysicsBridge.tirePartFriction("examplepack:slick_tire", 1.0));
   assertEquals(1.0, PhysicsBridge.tirePartFriction("examplepack:unconfigured_tire", 1.0),
     "a third, unconfigured tire key on the same vehicle must stay at the vehicle's own resolved value - independent wheel identities");
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 // Review finding (PR #28 comment, MINECRAFT-529): the ticket explicitly requires "valid/invalid
 // reload" coverage for installed tire friction overrides, not merely PhysicsBridge.tirePartFriction
 // exercised against a map installed directly via setForTest - that only proves the precedence
 // logic, not that a REAL reload (Overrides.apply(Supplier<Config>), the exact seam reload()
 // itself delegates through) actually swaps in a valid parts.<tire>.friction value and rejects
 // an invalid one while retaining the prior good override, the same "invalid reload retains
 // last-known-good" regression already covered for stallAngleDegrees/liftSlope/controlSurfaceSlope
 // above. Mirrors that test's exact structure, substituting a parts entry for a vehicles entry.

 @Test void validThenInvalidPartFrictionReloadAppliesThenRetainsThePriorGoodTireOverride() {
  try {
   var goodTire = new Overrides.Tuning(); goodTire.friction = 1.7;
   var goodResult = Overrides.apply(() -> new Overrides.Config(java.util.Map.of(), java.util.Map.of(),
     java.util.Map.of("examplepack:reloadable_tire", goodTire)));
   assertTrue(goodResult.success(), "a valid parts.<tire>.friction reload must apply cleanly");
   assertEquals(1.7, PhysicsBridge.tirePartFriction("examplepack:reloadable_tire", 1.0),
     "PhysicsBridge.tirePartFriction must observe the newly-applied valid part friction override");

   var invalidTire = new Overrides.Tuning(); invalidTire.friction = 0.0; // out of range: friction must be >0
   var badResult = Overrides.apply(() -> new Overrides.Config(java.util.Map.of(), java.util.Map.of(),
     java.util.Map.of("examplepack:reloadable_tire", invalidTire)));
   assertFalse(badResult.success(), "an out-of-range part friction (0.0) reload must be rejected, not silently applied");
   assertEquals(1.7, PhysicsBridge.tirePartFriction("examplepack:reloadable_tire", 1.0),
     "a rejected reload must retain the PRIOR GOOD part friction override, not the invalid value or the vehicle fallback");

   var tooHighTire = new Overrides.Tuning(); tooHighTire.friction = 3.5; // out of range: friction must be <=3
   var badResult2 = Overrides.apply(() -> new Overrides.Config(java.util.Map.of(), java.util.Map.of(),
     java.util.Map.of("examplepack:reloadable_tire", tooHighTire)));
   assertFalse(badResult2.success(), "an out-of-range part friction (3.5) reload must also be rejected");
   assertEquals(1.7, PhysicsBridge.tirePartFriction("examplepack:reloadable_tire", 1.0),
     "a second rejected reload must still retain the same prior good part friction override");
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 @Test void partEntryInheritingAnUnsupportedFieldThroughExtendsStillFailsTheSupportedFieldsCheck() {
  try {
   var template = new Overrides.Tuning(); template.brakeForce = 1000.0; // not supported for a part
   var part = new Overrides.Tuning(); part.extendsTemplate = "base"; part.driveForce = 9500.0;
   Overrides.setForTest(java.util.Map.of("base", template), java.util.Map.of(), java.util.Map.of());
   var merged = Overrides.effective(part);
   assertThrows(IllegalArgumentException.class, merged::validateSupportedForPart,
     "a part must not be able to smuggle an unsupported field in through extends");
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 // MINECRAFT-534: installed-tire rolling resistance overrides - rollingResistance is now a
 // supported part field, same exact shape as friction's own MINECRAFT-529 coverage above
 // (bounds, extends-inheritance, vehicle-fallthrough precedence, independent per-wheel
 // resolution, valid/invalid reload retention), but independent of grip - a tire can override
 // one field without the other.

 @Test void partEntrySettingRollingResistancePassesTheSupportedFieldsCheck() {
  var part = new Overrides.Tuning(); part.rollingResistance = 1.4;
  assertDoesNotThrow(part::validateSupportedForPart,
    "MINECRAFT-534: an installed tire part's own rollingResistance override must be an accepted part field");
 }

 @Test void partEntrySettingOutOfRangeRollingResistanceStillFailsPlainValidation() {
  var tooHigh = new Overrides.Tuning(); tooHigh.rollingResistance = 10.5;
  assertThrows(IllegalArgumentException.class, tooHigh::validate);
  var tooLow = new Overrides.Tuning(); tooLow.rollingResistance = 0.0;
  assertThrows(IllegalArgumentException.class, tooLow::validate);
 }

 @Test void partRollingResistanceOverrideInheritsThroughAnExtendsTemplate() {
  try {
   var template = new Overrides.Tuning(); template.rollingResistance = 2.5;
   var part = new Overrides.Tuning(); part.extendsTemplate = "tire-base";
   Overrides.setForTest(java.util.Map.of("tire-base", template), java.util.Map.of(), java.util.Map.of("packid:inherited_tire", part));
   var merged = Overrides.effectivePartOverride("packid:inherited_tire");
   assertEquals(2.5, merged.rollingResistance, "a part inheriting rollingResistance from a template must fold it in exactly like any other field");
   assertDoesNotThrow(merged::validateSupportedForPart);
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 @Test void tirePartRollingResistanceOverrideWinsOverTheVehicleResolvedValue() {
  try {
   var part = new Overrides.Tuning(); part.rollingResistance = 3.0;
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of("examplepack:draggy_tire", part));
   assertEquals(3.0, PhysicsBridge.tirePartRollingResistance("examplepack:draggy_tire", 1.0),
     "an explicit part-level rollingResistance override must win outright (replace, not multiply) over the vehicle's own resolved value");
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 @Test void tirePartRollingResistanceFallsThroughToTheVehicleResolvedValueWhenNoPartOverrideExists() {
  assertEquals(1.0, PhysicsBridge.tirePartRollingResistance("packid:ordinary_tire_with_no_override", 1.0),
    "no parts entry at all for this tire key must be a true no-op, reproducing the vehicle's resolved rollingResistance unchanged");
 }

 @Test void tirePartRollingResistanceResolvesIndependentlyPerWheelKeySoDifferentInstalledTiresGetDifferentDrag() {
  try {
   var draggy = new Overrides.Tuning(); draggy.rollingResistance = 4.0;
   var smooth = new Overrides.Tuning(); smooth.rollingResistance = 0.2;
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(),
     java.util.Map.of("examplepack:draggy_tire", draggy, "examplepack:smooth_tire", smooth));
   assertEquals(4.0, PhysicsBridge.tirePartRollingResistance("examplepack:draggy_tire", 1.0));
   assertEquals(0.2, PhysicsBridge.tirePartRollingResistance("examplepack:smooth_tire", 1.0));
   assertEquals(1.0, PhysicsBridge.tirePartRollingResistance("examplepack:unconfigured_tire", 1.0),
     "a third, unconfigured tire key on the same vehicle must stay at the vehicle's own resolved value - independent wheel identities");
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 @Test void tirePartRollingResistanceIsIndependentOfTirePartFrictionOnTheSamePart() {
  try {
   var part = new Overrides.Tuning(); part.rollingResistance = 3.5; // friction left unset
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of("examplepack:only_rolling_resistance_tire", part));
   assertEquals(3.5, PhysicsBridge.tirePartRollingResistance("examplepack:only_rolling_resistance_tire", 1.0));
   assertEquals(1.0, PhysicsBridge.tirePartFriction("examplepack:only_rolling_resistance_tire", 1.0),
     "a part overriding only rollingResistance must leave friction completely untouched - independent fields");
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 @Test void validThenInvalidPartRollingResistanceReloadAppliesThenRetainsThePriorGoodTireOverride() {
  try {
   var goodTire = new Overrides.Tuning(); goodTire.rollingResistance = 1.9;
   var goodResult = Overrides.apply(() -> new Overrides.Config(java.util.Map.of(), java.util.Map.of(),
     java.util.Map.of("examplepack:reloadable_tire", goodTire)));
   assertTrue(goodResult.success(), "a valid parts.<tire>.rollingResistance reload must apply cleanly");
   assertEquals(1.9, PhysicsBridge.tirePartRollingResistance("examplepack:reloadable_tire", 1.0),
     "PhysicsBridge.tirePartRollingResistance must observe the newly-applied valid part rollingResistance override");

   var invalidTire = new Overrides.Tuning(); invalidTire.rollingResistance = 0.0; // out of range: must be >0
   var badResult = Overrides.apply(() -> new Overrides.Config(java.util.Map.of(), java.util.Map.of(),
     java.util.Map.of("examplepack:reloadable_tire", invalidTire)));
   assertFalse(badResult.success(), "an out-of-range part rollingResistance (0.0) reload must be rejected, not silently applied");
   assertEquals(1.9, PhysicsBridge.tirePartRollingResistance("examplepack:reloadable_tire", 1.0),
     "a rejected reload must retain the PRIOR GOOD part rollingResistance override, not the invalid value or the vehicle fallback");

   var tooHighTire = new Overrides.Tuning(); tooHighTire.rollingResistance = 10.5; // out of range: must be <=10
   var badResult2 = Overrides.apply(() -> new Overrides.Config(java.util.Map.of(), java.util.Map.of(),
     java.util.Map.of("examplepack:reloadable_tire", tooHighTire)));
   assertFalse(badResult2.success(), "an out-of-range part rollingResistance (10.5) reload must also be rejected");
   assertEquals(1.9, PhysicsBridge.tirePartRollingResistance("examplepack:reloadable_tire", 1.0),
     "a second rejected reload must still retain the same prior good part rollingResistance override");
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 // Review finding (PR #30/Jira comment 54071): the production composition at stepGround's
 // FrictionContact-construction call site (surface.rollingResistanceMultiplier() * resolved
 // tire value) must NOT divide the resolved value back out by tuning.rollingResistance the way
 // tireGripRatio divides by tuning.friction - unlike friction, solveFriction has no separate
 // vehicle-baseline parameter for rolling resistance, so rollingResistanceMultiplier must carry
 // the FULL resolved value outright. These two regressions drive PhysicsBridge.
 // tirePartRollingResistance directly with a NON-UNIT vehicle baseline (1.0 alone cannot
 // distinguish "divides and cancels to itself" from "doesn't divide at all") and then perform
 // the exact same surface*resolved multiplication stepGround performs, proving neither a
 // no-part-override fallback nor a part-override replacement gets silently rescaled against the
 // vehicle's own configured baseline.

 @Test void nonUnitVehicleRollingResistanceComposesWithGroundSurfaceUnchangedWhenNoPartOverrideExists() {
  double vehicleRollingResistance = 2.0;
  double resolved = PhysicsBridge.tirePartRollingResistance("packid:no_override_tire", vehicleRollingResistance);
  assertEquals(2.0, resolved, 0, "with no part override, the resolved value must equal the configured vehicle baseline exactly, not a ratio against itself");
  double surfaceMultiplier = 1.0;
  double composed = surfaceMultiplier * resolved; // the exact stepGround composition, no division
  assertEquals(2.0, composed, 0, "a configured, non-unit vehicle rollingResistance with no part override must actually take effect on the composed multiplier, not be cancelled back to 1.0 by dividing against itself");
 }

 @Test void nonUnitVehicleRollingResistanceIsReplacedOutrightByAPartOverrideInTheComposedMultiplier() {
  try {
   var part = new Overrides.Tuning(); part.rollingResistance = 3.0;
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of("examplepack:overridden_tire", part));
   double vehicleRollingResistance = 2.0;
   double resolved = PhysicsBridge.tirePartRollingResistance("examplepack:overridden_tire", vehicleRollingResistance);
   assertEquals(3.0, resolved, 0, "a part override must replace the vehicle's own non-unit baseline outright, not multiply/divide against it");
   double surfaceMultiplier = 1.0;
   double composed = surfaceMultiplier * resolved; // the exact stepGround composition, no division
   assertEquals(3.0, composed, 0, "the composed multiplier must be surface*part (3.0), never surface*(part/vehicle) (1.5)");
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 @Test void enginePartDriveForceFallsThroughWhenThePartEntryExistsButDoesNotSetDriveForce() {
  try {
   var part = new Overrides.Tuning(); part.friction = 0.9; // sets a different field, not driveForce
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of("packid:partially_configured_engine", part));
   assertEquals(8000.0, PhysicsBridge.enginePartDriveForce("packid:partially_configured_engine", 8000.0),
     "a part entry that never sets driveForce must not be mistaken for a zero/fallback override");
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 // Overrides.reload(), like load(), needs a live FML config directory (and, for this ticket, a
 // full Gson on the runtime classpath, which a plain unit test's trimmed classpath does not
 // have either - confirmed directly: calling it here throws NoClassDefFoundError, an Error, not
 // one of the RuntimeException/IOException reload() catches, since production never needs to
 // catch a classpath problem that cannot occur on the real moddev/NeoForge runtime). Covered only
 // by the runtime-smoke/acceptance path, same as load() always has been (see this class's own
 // doc) - reload()'s actual per-field validation/extends-chain/last-known-good logic is already
 // exercised indirectly above via the extends/cycle/precedence tests against readAndValidate's
 // shared helper, minus only the file-I/O wrapper itself.

 // MINECRAFT-521 self-review: the ticket explicitly requires "invalid reload retaining last-good
 // values" as a regression, not merely Tuning.validate() throwing in isolation. Overrides.apply(
 // Supplier<Config>) (package-private test seam reload() itself now delegates through - see its
 // own doc) runs the real validate-then-swap-or-reject logic with no file I/O/Gson needed, the
 // same substitution GroundSurfaceOverridesTest already uses for its own loader's equivalent gap.

 @Test void invalidReloadRetainsThePriorGoodStallAngleLiftSlopeAndControlSurfaceSlope() {
  try {
   var good = new Overrides.Tuning();
   good.stallAngleDegrees = 20.0; good.liftSlope = 5.0; good.controlSurfaceSlope = 4.0;
   var goodResult = Overrides.apply(() -> new Overrides.Config(java.util.Map.of(), java.util.Map.of("packid:glider", good), java.util.Map.of()));
   assertTrue(goodResult.success(), "the known-good config itself must apply cleanly");
   assertEquals(20.0, Overrides.resolved("packid:glider").stallAngleDegrees);
   assertEquals(5.0, Overrides.resolved("packid:glider").liftSlope, 1e-9);
   assertEquals(4.0, Overrides.resolved("packid:glider").controlSurfaceSlope, 1e-9);

   var invalid = new Overrides.Tuning();
   invalid.stallAngleDegrees = 90.0; invalid.liftSlope = -1.0; invalid.controlSurfaceSlope = 999.0;
   var badResult = Overrides.apply(() -> new Overrides.Config(java.util.Map.of(), java.util.Map.of("packid:glider", invalid), java.util.Map.of()));
   assertFalse(badResult.success(), "an out-of-range reload for these fields must be rejected, not silently applied");

   assertEquals(20.0, Overrides.resolved("packid:glider").stallAngleDegrees,
     "a rejected reload must retain the PRIOR GOOD value, not the invalid one or the flat fallback");
   assertEquals(5.0, Overrides.resolved("packid:glider").liftSlope, 1e-9,
     "a rejected reload must retain the PRIOR GOOD value, not the invalid one or the flat fallback");
   assertEquals(4.0, Overrides.resolved("packid:glider").controlSurfaceSlope, 1e-9,
     "a rejected reload must retain the PRIOR GOOD value, not the invalid one or the flat fallback");
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 // MINECRAFT-536 self-review: same "invalid reload retaining last-good values" regression
 // already covered for stallAngleDegrees/liftSlope/controlSurfaceSlope above, for the new
 // parasiticDragMultiplier/oswaldEfficiency fields.

 @Test void invalidReloadRetainsThePriorGoodParasiticDragMultiplierAndOswaldEfficiency() {
  try {
   var good = new Overrides.Tuning();
   good.parasiticDragMultiplier = 1.4; good.oswaldEfficiency = 0.7;
   var goodResult = Overrides.apply(() -> new Overrides.Config(java.util.Map.of(), java.util.Map.of("packid:glider3", good), java.util.Map.of()));
   assertTrue(goodResult.success(), "the known-good config itself must apply cleanly");
   assertEquals(1.4, Overrides.resolved("packid:glider3").parasiticDragMultiplier, 1e-9);
   assertEquals(0.7, Overrides.resolved("packid:glider3").oswaldEfficiency, 1e-9);

   var invalid = new Overrides.Tuning();
   invalid.parasiticDragMultiplier = -1.0; invalid.oswaldEfficiency = 1.5;
   var badResult = Overrides.apply(() -> new Overrides.Config(java.util.Map.of(), java.util.Map.of("packid:glider3", invalid), java.util.Map.of()));
   assertFalse(badResult.success(), "an out-of-range reload for these fields must be rejected, not silently applied");

   assertEquals(1.4, Overrides.resolved("packid:glider3").parasiticDragMultiplier, 1e-9,
     "a rejected reload must retain the PRIOR GOOD value, not the invalid one or the flat fallback");
   assertEquals(0.7, Overrides.resolved("packid:glider3").oswaldEfficiency, 1e-9,
     "a rejected reload must retain the PRIOR GOOD value, not the invalid one or the flat fallback");
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 @Test void invalidReloadViaAThrowingParseAlsoRetainsThePriorGoodValues() {
  // The other half of the substitution GroundSurfaceOverridesTest already established: a
  // configSupplier that throws (standing in for a real malformed-JSON parse failure) must be
  // rejected exactly like a validation failure, never treated differently.
  try {
   var good = new Overrides.Tuning(); good.stallAngleDegrees = 12.0;
   Overrides.apply(() -> new Overrides.Config(java.util.Map.of(), java.util.Map.of("packid:glider2", good), java.util.Map.of()));
   assertEquals(12.0, Overrides.resolved("packid:glider2").stallAngleDegrees);

   var badResult = Overrides.apply(() -> { throw new RuntimeException("malformed json"); });
   assertFalse(badResult.success());
   assertEquals(12.0, Overrides.resolved("packid:glider2").stallAngleDegrees,
     "a throwing parse must retain the prior good state, same as a validation failure does");
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 // MINECRAFT-523: suspensionDampingRatio - dimensionless, strictly positive, capped at 2.0
 // damping ratio PhysicsBridge.suspensionDamper/suspensionDamperForShare/effectiveMassDamper
 // already apply; 0.7 (FALLBACK) reproduces the exact prior hardcoded constant. The 2.0 cap
 // (review finding, ticket comment 53827) keeps a real margin below the measured ~2.2-2.3
 // numerical-instability onset Body.stepGround's explicit-damping-term integration has at this
 // project's documented default example config - see validate()'s own doc and
 // PhysicsBridgeSuspensionDampingRatioTest's stability sweep.

 @Test void suspensionDampingRatioOutOfRangeFailsValidation() {
  var zero = new Overrides.Tuning(); zero.suspensionDampingRatio = 0.0; // never valid - an undamped oscillator
  assertThrows(IllegalArgumentException.class, zero::validate);
  var negative = new Overrides.Tuning(); negative.suspensionDampingRatio = -0.7;
  assertThrows(IllegalArgumentException.class, negative::validate);
  var tooLarge = new Overrides.Tuning(); tooLarge.suspensionDampingRatio = 2.01;
  assertThrows(IllegalArgumentException.class, tooLarge::validate);
  var nan = new Overrides.Tuning(); nan.suspensionDampingRatio = Double.NaN;
  assertThrows(IllegalArgumentException.class, nan::validate);
  var criticallyDamped = new Overrides.Tuning(); criticallyDamped.suspensionDampingRatio = 1.0;
  assertDoesNotThrow(criticallyDamped::validate);
  var upperBound = new Overrides.Tuning(); upperBound.suspensionDampingRatio = 2.0; // inclusive upper bound is allowed
  assertDoesNotThrow(upperBound::validate);
  var nearZero = new Overrides.Tuning(); nearZero.suspensionDampingRatio = 0.01; // exclusive lower bound - just above zero is allowed
  assertDoesNotThrow(nearZero::validate);
 }

 @Test void suspensionDampingRatioParticipatesInTheFullPrecedenceChain() {
  try {
   var override = new Overrides.Tuning(); override.suspensionDampingRatio = 1.4;
   Overrides.setForTest(java.util.Map.of("packid:stiff_damper_vehicle", override));
   assertEquals(1.4, Overrides.resolved("packid:stiff_damper_vehicle").suspensionDampingRatio,
     "an explicit operator override must take precedence over the 0.7 fallback");

   var packMetadata = new Overrides.Tuning(); packMetadata.suspensionDampingRatio = 0.9;
   assertEquals(0.9, Overrides.resolved("packid:not_configured", packMetadata, null, null).suspensionDampingRatio,
     "pack metadata must win over the flat fallback when no operator override exists");
  } finally {
   Overrides.setForTest(java.util.Map.of());
  }
 }

 @Test void suspensionDampingRatioIsRejectedOnAPartOverride() {
  var part = new Overrides.Tuning(); part.suspensionDampingRatio = 1.0;
  assertThrows(IllegalArgumentException.class, part::validateSupportedForPart);
 }

 @Test void suspensionDampingRatioIsInheritedThroughExtends() {
  try {
   var template = new Overrides.Tuning(); template.suspensionDampingRatio = 0.5;
   var child = new Overrides.Tuning(); child.extendsTemplate = "soft-damper";
   Overrides.setForTest(java.util.Map.of("soft-damper", template), java.util.Map.of("packid:inherits_damping", child), java.util.Map.of());
   assertEquals(0.5, Overrides.resolved("packid:inherits_damping").suspensionDampingRatio,
     "a child entry leaving suspensionDampingRatio unset must inherit its template's own value");
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 // MINECRAFT-523 review (comments 53839/53840): the flat 2.0 ceiling alone is not provably
 // stable at every suspensionTravel - a smaller configured travel raises the per-wheel natural
 // frequency enough to destabilize ratios well under 2.0 too (confirmed by an independent
 // settling sweep reproducing the reviewer's own numbers exactly - see
 // PhysicsBridgeSuspensionDampingRatioTest). validate() now also rejects a configured
 // (suspensionDampingRatio, suspensionTravel) PAIR whenever it would already be numerically
 // unstable under Body.stepGround's own integration, not just an out-of-[0,2.0]-range ratio.

 @Test void suspensionDampingRatioIsRejectedWhenUnstableForItsOwnConfiguredSuspensionTravel() {
  // At travel=0.2 (well within suspensionTravel's own independently-valid (0,1] range), ratio
  // 2.0 is within the flat [0,2.0] range but was independently confirmed (sweep) to never
  // converge - must still be rejected by the travel-aware check.
  var unstablePair = new Overrides.Tuning(); unstablePair.suspensionTravel = 0.2; unstablePair.suspensionDampingRatio = 2.0;
  assertThrows(IllegalArgumentException.class, unstablePair::validate,
    "ratio 2.0 at travel 0.2 is numerically unstable (confirmed by sweep) and must be rejected even though 2.0 alone is in range");
  // A ratio comfortably below that travel's own stability bound (~1.776) must still validate.
  var stablePair = new Overrides.Tuning(); stablePair.suspensionTravel = 0.2; stablePair.suspensionDampingRatio = 1.6;
  assertDoesNotThrow(stablePair::validate,
    "ratio 1.6 at travel 0.2 is below that travel's own stability bound and must still be accepted");
  // The documented default travel (0.7 ratio, no suspensionTravel override at all) must remain
  // exactly as valid as before this review fix - the cross-field check must not retune defaults.
  var defaultRatioNoTravelOverride = new Overrides.Tuning(); defaultRatioNoTravelOverride.suspensionDampingRatio = 0.7;
  assertDoesNotThrow(defaultRatioNoTravelOverride::validate,
    "the documented 0.7 default, with no suspensionTravel override, must remain valid against the assumed 0.3 fallback travel");
  // An extremely small travel's own existing (0,1]-range validity is untouched by this field -
  // this check only ever REJECTS an unstable ratio for that travel, never widens or narrows
  // suspensionTravel's own accepted range.
  var tinyTravelNoRatio = new Overrides.Tuning(); tinyTravelNoRatio.suspensionTravel = 0.02;
  assertDoesNotThrow(tinyTravelNoRatio::validate,
    "suspensionTravel alone, with no suspensionDampingRatio configured at all, must be unaffected by this cross-field check");
 }

 /**
  * Review finding (comment 53844): the underlying linearized stability condition is a STRICT
  * inequality - at the computed bound EXACTLY, the integration sustains a non-decaying
  * oscillation (confirmed by an independent sweep: ratio 1.7758240724699157 at travel 0.2
  * alternates .093125/.106875 around a .1 target forever, never converging), so a naive
  * non-strict comparison would wrongly accept it. validate() must reject the exact boundary
  * too, not merely values strictly past it.
  */
 @Test void suspensionDampingRatioAtTheExactComputedStabilityBoundaryIsStillRejected() {
  double exactBoundaryRatioAtTravelPoint2 = 1.7758240724699157;
  var atExactBoundary = new Overrides.Tuning(); atExactBoundary.suspensionTravel = 0.2; atExactBoundary.suspensionDampingRatio = exactBoundaryRatioAtTravelPoint2;
  assertThrows(IllegalArgumentException.class, atExactBoundary::validate,
    "the exact computed stability boundary itself is a sustained non-decaying oscillation (confirmed by sweep), not actually stable, and must be rejected");
  // Comfortably below the boundary (the margin applied) must still validate.
  var justBelowBoundary = new Overrides.Tuning(); justBelowBoundary.suspensionTravel = 0.2; justBelowBoundary.suspensionDampingRatio = exactBoundaryRatioAtTravelPoint2*0.99;
  assertDoesNotThrow(justBelowBoundary::validate,
    "a ratio comfortably below the computed boundary (confirmed by sweep to actually converge) must still be accepted");
 }

 // MINECRAFT-528: suspensionSpringMultiplier - dimensionless, strictly positive, flat-capped at
 // 4.0 multiplier on the calibrated per-wheel spring rate PhysicsBridge.suspensionSpring/
 // suspensionSpringForShare already compute; 1.0 (FALLBACK) is a pure no-op reproducing the
 // exact prior calibrated rate. See suspensionSpringMultiplier's own doc for why the real
 // stability guard is the combined cross-field check below (shared with suspensionDampingRatio),
 // not this flat range alone.

 @Test void suspensionSpringMultiplierOutOfRangeFailsValidation() {
  var zero = new Overrides.Tuning(); zero.suspensionSpringMultiplier = 0.0; // never valid - not a spring at all
  assertThrows(IllegalArgumentException.class, zero::validate);
  var negative = new Overrides.Tuning(); negative.suspensionSpringMultiplier = -1.0;
  assertThrows(IllegalArgumentException.class, negative::validate);
  var tooLarge = new Overrides.Tuning(); tooLarge.suspensionSpringMultiplier = 4.01;
  assertThrows(IllegalArgumentException.class, tooLarge::validate);
  var nan = new Overrides.Tuning(); nan.suspensionSpringMultiplier = Double.NaN;
  assertThrows(IllegalArgumentException.class, nan::validate);
  var noOp = new Overrides.Tuning(); noOp.suspensionSpringMultiplier = 1.0;
  assertDoesNotThrow(noOp::validate);
  var upperBound = new Overrides.Tuning(); upperBound.suspensionSpringMultiplier = 4.0; // inclusive upper bound is allowed
  assertDoesNotThrow(upperBound::validate);
 }

 /**
  * Review finding (comment 53996): the configured lower range was not physically bounded by
  * available suspension travel - the prior test's own {@code nearZero = 0.01} was wrongly
  * accepted as valid, even though its equilibrium ({@code (travel*.5)/0.01} = 15 blocks at the
  * documented 0.3 default travel) is nowhere near reachable under
  * {@code PhysicsBridge}'s own travel-bounded compression clamp - damping supplies no static
  * support at zero velocity, so it cannot make up that shortfall at rest. The lower bound is now
  * a strict {@code 0.5} - see {@code suspensionSpringMultiplier}'s own doc for the full
  * {@code (travel*.5)/m < travel} derivation (travel cancels out entirely, so this bound holds
  * regardless of whatever {@code suspensionTravel} is actually configured).
  */
 @Test void suspensionSpringMultiplierAtOrBelowTheReachableEquilibriumLowerBoundFailsValidation() {
  var oldlyAcceptedNearZero = new Overrides.Tuning(); oldlyAcceptedNearZero.suspensionSpringMultiplier = 0.01;
  assertThrows(IllegalArgumentException.class, oldlyAcceptedNearZero::validate,
    "0.01 must now be rejected - its equilibrium is nowhere near reachable under any valid suspensionTravel");
  var exactlyAtBound = new Overrides.Tuning(); exactlyAtBound.suspensionSpringMultiplier = 0.5;
  assertThrows(IllegalArgumentException.class, exactlyAtBound::validate,
    "0.5 exactly means the equilibrium IS suspensionTravel - fully bottomed out, not a working suspension, and must be rejected same as suspensionDampingRatio's own exact-boundary rejection");
  var justBelowBound = new Overrides.Tuning(); justBelowBound.suspensionSpringMultiplier = 0.49;
  assertThrows(IllegalArgumentException.class, justBelowBound::validate);
  var justAboveBound = new Overrides.Tuning(); justAboveBound.suspensionSpringMultiplier = 0.51;
  assertDoesNotThrow(justAboveBound::validate,
    "just above the bound must remain accepted - this is a genuine lower bound, not a disguised ban on softer-than-default springs");
  // The bound holds regardless of suspensionTravel - confirming the travel-cancels-out claim at
  // a travel other than the documented 0.3 default.
  var belowBoundAtDifferentTravel = new Overrides.Tuning(); belowBoundAtDifferentTravel.suspensionTravel = 0.6; belowBoundAtDifferentTravel.suspensionSpringMultiplier = 0.49;
  assertThrows(IllegalArgumentException.class, belowBoundAtDifferentTravel::validate,
    "0.49 must still be rejected at a different suspensionTravel - the bound is independent of travel");
  var aboveBoundAtDifferentTravel = new Overrides.Tuning(); aboveBoundAtDifferentTravel.suspensionTravel = 0.6; aboveBoundAtDifferentTravel.suspensionSpringMultiplier = 0.51;
  assertDoesNotThrow(aboveBoundAtDifferentTravel::validate,
    "0.51 must still be accepted at a different suspensionTravel - the bound is independent of travel");
 }

 @Test void suspensionSpringMultiplierParticipatesInTheFullPrecedenceChain() {
  try {
   var override = new Overrides.Tuning(); override.suspensionSpringMultiplier = 1.5;
   Overrides.setForTest(java.util.Map.of("packid:stiff_spring_vehicle", override));
   assertEquals(1.5, Overrides.resolved("packid:stiff_spring_vehicle").suspensionSpringMultiplier,
     "an explicit operator override must take precedence over the 1.0 fallback");

   var packMetadata = new Overrides.Tuning(); packMetadata.suspensionSpringMultiplier = 0.8;
   assertEquals(0.8, Overrides.resolved("packid:not_configured", packMetadata, null, null).suspensionSpringMultiplier,
     "pack metadata must win over the flat fallback when no operator override exists");
  } finally {
   Overrides.setForTest(java.util.Map.of());
  }
 }

 @Test void suspensionSpringMultiplierIsRejectedOnAPartOverride() {
  var part = new Overrides.Tuning(); part.suspensionSpringMultiplier = 1.2;
  assertThrows(IllegalArgumentException.class, part::validateSupportedForPart);
 }

 @Test void suspensionSpringMultiplierIsInheritedThroughExtends() {
  try {
   var template = new Overrides.Tuning(); template.suspensionSpringMultiplier = 1.3;
   var child = new Overrides.Tuning(); child.extendsTemplate = "stiff-spring";
   Overrides.setForTest(java.util.Map.of("stiff-spring", template), java.util.Map.of("packid:inherits_spring", child), java.util.Map.of());
   assertEquals(1.3, Overrides.resolved("packid:inherits_spring").suspensionSpringMultiplier,
     "a child entry leaving suspensionSpringMultiplier unset must inherit its template's own value");
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 @Test void invalidReloadRetainsThePriorGoodSuspensionSpringMultiplier() {
  try {
   var good = new Overrides.Tuning(); good.suspensionSpringMultiplier = 1.5;
   var goodResult = Overrides.apply(() -> new Overrides.Config(java.util.Map.of(), java.util.Map.of("packid:springy", good), java.util.Map.of()));
   assertTrue(goodResult.success(), "the known-good config itself must apply cleanly");
   assertEquals(1.5, Overrides.resolved("packid:springy").suspensionSpringMultiplier);

   var invalid = new Overrides.Tuning(); invalid.suspensionSpringMultiplier = -2.0;
   var badResult = Overrides.apply(() -> new Overrides.Config(java.util.Map.of(), java.util.Map.of("packid:springy", invalid), java.util.Map.of()));
   assertFalse(badResult.success(), "an out-of-range reload must be rejected, not silently applied");

   assertEquals(1.5, Overrides.resolved("packid:springy").suspensionSpringMultiplier,
     "a rejected reload must retain the PRIOR GOOD value, not the invalid one or the flat fallback");
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 // MINECRAFT-528: scaling the spring raises the same per-wheel natural frequency
 // suspensionDampingRatio's own travel-aware cross-field check is written against (see
 // maxStableSuspensionDampingRatio's own doc, now generalized to take the configured
 // suspensionSpringMultiplier too) - the two fields share ONE combined stability check, so a
 // configured multiplier can destabilize even the unconfigured 0.7 default ratio, and a
 // configured ratio must be checked against the unconfigured 1.0 default multiplier too.

 @Test void suspensionSpringMultiplierAloneCanBeRejectedForDestabilizingTheUnconfiguredDefaultRatio() {
  // At a smaller, independently-valid suspensionTravel (0.2, same travel M523's own cross-field
  // tests already use), the unconfigured default 0.7 ratio has much less headroom than at the
  // documented 0.3 default - a spring multiplier within its OWN flat [0,4.0] range can still
  // raise the natural frequency past what even that default ratio can stay stable against, with
  // no suspensionDampingRatio override in sight.
  var destabilizing = new Overrides.Tuning(); destabilizing.suspensionTravel = 0.2; destabilizing.suspensionSpringMultiplier = 4.0;
  assertThrows(IllegalArgumentException.class, destabilizing::validate,
    "a spring multiplier must be rejected when it destabilizes the unconfigured default damping ratio at this travel, even though 4.0 alone is within suspensionSpringMultiplier's own flat range");
  // A modest multiplier at the same smaller travel must remain valid at the default ratio.
  var modest = new Overrides.Tuning(); modest.suspensionTravel = 0.2; modest.suspensionSpringMultiplier = 2.0;
  assertDoesNotThrow(modest::validate,
    "a modest spring multiplier must remain valid against the unconfigured default damping ratio at this travel");
  // An unconfigured multiplier (no suspensionSpringMultiplier field at all) must remain exactly
  // as valid as before this ticket - this check must not retune a vehicle that never touches
  // either field.
  var untouched = new Overrides.Tuning();
  assertDoesNotThrow(untouched::validate,
    "leaving both suspensionSpringMultiplier and suspensionDampingRatio unset must be entirely unaffected by this cross-field check");
 }

 @Test void suspensionSpringMultiplierAndSuspensionDampingRatioAreCheckedTogetherAsAPair() {
  // A ratio that is comfortably stable against the UNCONFIGURED 1.0 multiplier can still be
  // rejected once a configured multiplier raises the natural frequency enough - the two fields
  // must be validated as a genuine pair, not independently.
  var ratioAloneOk = new Overrides.Tuning(); ratioAloneOk.suspensionDampingRatio = 1.8;
  assertDoesNotThrow(ratioAloneOk::validate,
    "ratio 1.8 alone (unconfigured 1.0 multiplier) must remain valid - unchanged from suspensionDampingRatio's own pre-528 behavior");
  var ratioWithDestabilizingMultiplier = new Overrides.Tuning();
  ratioWithDestabilizingMultiplier.suspensionDampingRatio = 1.8; ratioWithDestabilizingMultiplier.suspensionSpringMultiplier = 2.5;
  assertThrows(IllegalArgumentException.class, ratioWithDestabilizingMultiplier::validate,
    "the same ratio 1.8, once paired with a destabilizing spring multiplier, must be rejected - the two fields are one combined stability check, not two independent ones");
  // The same multiplier, paired with a smaller/safer ratio, must still be accepted.
  var saferPair = new Overrides.Tuning();
  saferPair.suspensionDampingRatio = 0.9; saferPair.suspensionSpringMultiplier = 2.5;
  assertDoesNotThrow(saferPair::validate,
    "a smaller ratio paired with the same spring multiplier must still be accepted when the pair is actually stable");
 }

 // MINECRAFT-535: longitudinal/lateral/verticalWaterDragMultiplier - bounded body-local
 // directional multipliers on top of the existing isotropic waterDrag, each defaulting to the
 // same 1.0 no-op as suspensionSpringMultiplier.

 @Test void directionalWaterDragMultipliersOutOfRangeFailValidation() {
  for (String field : new String[]{"longitudinal", "lateral", "vertical"}) {
   var negative = new Overrides.Tuning(); setDirectionalMultiplier(negative, field, -1.0);
   assertThrows(IllegalArgumentException.class, negative::validate, field + ": negative must be rejected");
   var tooLarge = new Overrides.Tuning(); setDirectionalMultiplier(tooLarge, field, 5.01);
   assertThrows(IllegalArgumentException.class, tooLarge::validate, field + ": above the flat 5.0 ceiling must be rejected");
   var nan = new Overrides.Tuning(); setDirectionalMultiplier(nan, field, Double.NaN);
   assertThrows(IllegalArgumentException.class, nan::validate, field + ": NaN must be rejected");
   var zero = new Overrides.Tuning(); setDirectionalMultiplier(zero, field, 0.0); // a real, legitimate extreme - not degenerate
   assertDoesNotThrow(zero::validate, field + ": exact 0 (no drag on this axis at all) must be allowed");
   var upperBound = new Overrides.Tuning(); setDirectionalMultiplier(upperBound, field, 5.0);
   assertDoesNotThrow(upperBound::validate, field + ": exact 5.0 (inclusive upper bound) must be allowed");
   var noOp = new Overrides.Tuning(); setDirectionalMultiplier(noOp, field, 1.0);
   assertDoesNotThrow(noOp::validate, field + ": the 1.0 fallback value must always be valid");
  }
 }

 @Test void directionalWaterDragMultipliersParticipateInTheFullPrecedenceChain() {
  try {
   var override = new Overrides.Tuning();
   override.longitudinalWaterDragMultiplier = 2.0; override.lateralWaterDragMultiplier = 0.5; override.verticalWaterDragMultiplier = 3.0;
   Overrides.setForTest(java.util.Map.of("packid:directional_drag_vehicle", override));
   var r = Overrides.resolved("packid:directional_drag_vehicle");
   assertEquals(2.0, r.longitudinalWaterDragMultiplier, "an explicit operator override must take precedence over the 1.0 fallback");
   assertEquals(0.5, r.lateralWaterDragMultiplier);
   assertEquals(3.0, r.verticalWaterDragMultiplier);
  } finally {
   Overrides.setForTest(java.util.Map.of());
  }
 }

 @Test void directionalWaterDragMultipliersAreRejectedOnAPartOverride() {
  var longitudinal = new Overrides.Tuning(); longitudinal.longitudinalWaterDragMultiplier = 1.5;
  assertThrows(IllegalArgumentException.class, longitudinal::validateSupportedForPart);
  var lateral = new Overrides.Tuning(); lateral.lateralWaterDragMultiplier = 1.5;
  assertThrows(IllegalArgumentException.class, lateral::validateSupportedForPart);
  var vertical = new Overrides.Tuning(); vertical.verticalWaterDragMultiplier = 1.5;
  assertThrows(IllegalArgumentException.class, vertical::validateSupportedForPart);
 }

 @Test void directionalWaterDragMultipliersAreInheritedThroughExtends() {
  try {
   var template = new Overrides.Tuning(); template.longitudinalWaterDragMultiplier = 2.5;
   var child = new Overrides.Tuning(); child.extendsTemplate = "slippery-hull";
   Overrides.setForTest(java.util.Map.of("slippery-hull", template), java.util.Map.of("packid:inherits_drag", child), java.util.Map.of());
   assertEquals(2.5, Overrides.resolved("packid:inherits_drag").longitudinalWaterDragMultiplier,
     "a child entry leaving longitudinalWaterDragMultiplier unset must inherit its template's own value");
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 private static void setDirectionalMultiplier(Overrides.Tuning t, String field, double value) {
  switch (field) {
   case "longitudinal" -> t.longitudinalWaterDragMultiplier = value;
   case "lateral" -> t.lateralWaterDragMultiplier = value;
   case "vertical" -> t.verticalWaterDragMultiplier = value;
   default -> throw new IllegalArgumentException(field);
  }
 }

 // MINECRAFT-539: suspensionSpringMultiplierFront/Rear, suspensionDampingRatioFront/Rear -
 // per-geometric-axle overrides of suspensionSpringMultiplier/suspensionDampingRatio, same bounds
 // as those base fields, no FALLBACK entry (unset axle field inherits the vehicle's own already-
 // resolved base value - see AxleSuspensionDistribution's own doc), participating in the SAME
 // combined travel-aware stability check as the base fields, generalized per axle.

 @Test void axleSpringAndDampingFieldsOutOfRangeFailValidation() {
  var zeroRatioFront = new Overrides.Tuning(); zeroRatioFront.suspensionDampingRatioFront = 0.0;
  assertThrows(IllegalArgumentException.class, zeroRatioFront::validate);
  var tooLargeRatioRear = new Overrides.Tuning(); tooLargeRatioRear.suspensionDampingRatioRear = 2.01;
  assertThrows(IllegalArgumentException.class, tooLargeRatioRear::validate);
  var nanRatioFront = new Overrides.Tuning(); nanRatioFront.suspensionDampingRatioFront = Double.NaN;
  assertThrows(IllegalArgumentException.class, nanRatioFront::validate);
  var atBoundMultiplierFront = new Overrides.Tuning(); atBoundMultiplierFront.suspensionSpringMultiplierFront = 0.5; // fully bottomed out, same reasoning as the base field
  assertThrows(IllegalArgumentException.class, atBoundMultiplierFront::validate);
  var tooLargeMultiplierRear = new Overrides.Tuning(); tooLargeMultiplierRear.suspensionSpringMultiplierRear = 4.01;
  assertThrows(IllegalArgumentException.class, tooLargeMultiplierRear::validate);
  var validRatioFront = new Overrides.Tuning(); validRatioFront.suspensionDampingRatioFront = 1.0;
  assertDoesNotThrow(validRatioFront::validate);
  var validMultiplierRear = new Overrides.Tuning(); validMultiplierRear.suspensionSpringMultiplierRear = 1.0;
  assertDoesNotThrow(validMultiplierRear::validate);
 }

 @Test void unsetAxleFieldsHaveNoFallbackAndStayNullWhenNeverConfigured() {
  // No FALLBACK entry for any of the four fields (mirrors brakeBiasFront/toeFrontDegrees above)
  // - a vehicle that never sets any of them must stay null through resolution
  // (AxleSuspensionDistribution's own inheritance, not a second independently-resolved default,
  // is what substitutes the base value at runtime).
  assertNull(Overrides.resolved("packid:not_configured").suspensionDampingRatioFront);
  assertNull(Overrides.resolved("packid:not_configured").suspensionDampingRatioRear);
  assertNull(Overrides.resolved("packid:not_configured").suspensionSpringMultiplierFront);
  assertNull(Overrides.resolved("packid:not_configured").suspensionSpringMultiplierRear);
  assertNull(Overrides.defaultTuning().suspensionDampingRatioFront);
 }

 @Test void axleSpringAndDampingFieldsParticipateInTheFullPrecedenceChain() {
  try {
   var override = new Overrides.Tuning();
   override.suspensionDampingRatioFront = 1.4; override.suspensionSpringMultiplierRear = 0.7;
   Overrides.setForTest(java.util.Map.of("packid:axle_tuned_vehicle", override));
   var resolved = Overrides.resolved("packid:axle_tuned_vehicle");
   assertEquals(1.4, resolved.suspensionDampingRatioFront,
     "an explicit operator override must take precedence and stay non-null, unlike the no-FALLBACK base resolution");
   assertEquals(0.7, resolved.suspensionSpringMultiplierRear);
   assertNull(resolved.suspensionDampingRatioRear, "the unconfigured axle field must stay null, not fall back to any flat constant");

   var packMetadata = new Overrides.Tuning(); packMetadata.suspensionSpringMultiplierFront = 1.6;
   assertEquals(1.6, Overrides.resolved("packid:not_configured", packMetadata, null, null).suspensionSpringMultiplierFront,
     "pack metadata must win over the (nonexistent) fallback when no operator override exists");
  } finally {
   Overrides.setForTest(java.util.Map.of());
  }
 }

 @Test void axleSpringAndDampingFieldsAreRejectedOnAPartOverride() {
  var dampingFront = new Overrides.Tuning(); dampingFront.suspensionDampingRatioFront = 1.0;
  assertThrows(IllegalArgumentException.class, dampingFront::validateSupportedForPart);
  var dampingRear = new Overrides.Tuning(); dampingRear.suspensionDampingRatioRear = 1.0;
  assertThrows(IllegalArgumentException.class, dampingRear::validateSupportedForPart);
  var springFront = new Overrides.Tuning(); springFront.suspensionSpringMultiplierFront = 1.2;
  assertThrows(IllegalArgumentException.class, springFront::validateSupportedForPart);
  var springRear = new Overrides.Tuning(); springRear.suspensionSpringMultiplierRear = 1.2;
  assertThrows(IllegalArgumentException.class, springRear::validateSupportedForPart);
 }

 @Test void axleSpringAndDampingFieldsAreInheritedThroughExtends() {
  try {
   var template = new Overrides.Tuning();
   template.suspensionDampingRatioFront = 1.1; template.suspensionSpringMultiplierRear = 0.8;
   var child = new Overrides.Tuning(); child.extendsTemplate = "axle-tuned-template";
   Overrides.setForTest(java.util.Map.of("axle-tuned-template", template), java.util.Map.of("packid:inherits_axle_tuning", child), java.util.Map.of());
   var resolved = Overrides.resolved("packid:inherits_axle_tuning");
   assertEquals(1.1, resolved.suspensionDampingRatioFront,
     "a child entry leaving suspensionDampingRatioFront unset must inherit its template's own value");
   assertEquals(0.8, resolved.suspensionSpringMultiplierRear);
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 @Test void invalidReloadRetainsThePriorGoodAxleSuspensionFields() {
  try {
   var good = new Overrides.Tuning(); good.suspensionDampingRatioFront = 1.2;
   var goodResult = Overrides.apply(() -> new Overrides.Config(java.util.Map.of(), java.util.Map.of("packid:axle_reload", good), java.util.Map.of()));
   assertTrue(goodResult.success(), "the known-good config itself must apply cleanly");
   assertEquals(1.2, Overrides.resolved("packid:axle_reload").suspensionDampingRatioFront);

   var invalid = new Overrides.Tuning(); invalid.suspensionDampingRatioFront = -3.0;
   var badResult = Overrides.apply(() -> new Overrides.Config(java.util.Map.of(), java.util.Map.of("packid:axle_reload", invalid), java.util.Map.of()));
   assertFalse(badResult.success(), "an out-of-range reload must be rejected, not silently applied");

   assertEquals(1.2, Overrides.resolved("packid:axle_reload").suspensionDampingRatioFront,
     "a rejected reload must retain the PRIOR GOOD value, not the invalid one or null");

   // Removal (a reload that omits the field entirely) must successfully fall back to null
   // (inherit the base value) - the live-reload-retention above must not make a legitimate
   // removal impossible.
   var removed = new Overrides.Tuning();
   var removedResult = Overrides.apply(() -> new Overrides.Config(java.util.Map.of(), java.util.Map.of("packid:axle_reload", removed), java.util.Map.of()));
   assertTrue(removedResult.success(), "removing the field entirely must itself be a valid reload");
   assertNull(Overrides.resolved("packid:axle_reload").suspensionDampingRatioFront,
     "a reload that no longer sets the field must resolve back to null (inherit base), not retain the old value");
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 @Test void axleOverrideAloneCanBeRejectedForDestabilizingTheUnconfiguredDefaultOnThatAxle() {
  // Same shape as suspensionSpringMultiplierAloneCanBeRejectedForDestabilizingTheUnconfiguredDefaultRatio
  // above, generalized per-axle: a front-only multiplier override must be checked against the
  // UNCONFIGURED default 0.7 ratio for the front axle specifically, even with no vehicle-wide or
  // rear-axle field touched at all.
  var destabilizingFront = new Overrides.Tuning();
  destabilizingFront.suspensionTravel = 0.2; destabilizingFront.suspensionSpringMultiplierFront = 4.0;
  assertThrows(IllegalArgumentException.class, destabilizingFront::validate,
    "a front-axle-only spring multiplier must be rejected when it destabilizes the unconfigured default ratio on that axle");
  var destabilizingRearRatio = new Overrides.Tuning();
  destabilizingRearRatio.suspensionTravel = 0.2; destabilizingRearRatio.suspensionDampingRatioRear = 1.77;
  assertThrows(IllegalArgumentException.class, destabilizingRearRatio::validate,
    "a rear-axle-only damping ratio must be rejected when it is unstable against the unconfigured default multiplier on that axle");
  // A safe axle-specific pair must remain accepted.
  var safeFront = new Overrides.Tuning();
  safeFront.suspensionTravel = 0.2; safeFront.suspensionSpringMultiplierFront = 1.0; safeFront.suspensionDampingRatioFront = 0.9;
  assertDoesNotThrow(safeFront::validate);
 }

 @Test void invalidAxlePairsAreRejectedEvenWhenTheOtherAxleAndTheVehicleWideFieldsAreSafe() {
  // The vehicle-wide pair (0.7/1.0 defaults) and the REAR axle are both safe here - only the
  // FRONT axle's own (multiplier, ratio) pair is destabilizing - the per-axle generalization must
  // still catch it rather than only checking the vehicle-wide base pair.
  var frontOnlyUnstablePair = new Overrides.Tuning();
  frontOnlyUnstablePair.suspensionTravel = 0.2;
  frontOnlyUnstablePair.suspensionSpringMultiplierFront = 2.5;
  frontOnlyUnstablePair.suspensionDampingRatioFront = 1.8;
  frontOnlyUnstablePair.suspensionDampingRatioRear = 0.5; // safe on its own
  assertThrows(IllegalArgumentException.class, frontOnlyUnstablePair::validate,
    "an unstable front-axle (multiplier,ratio) pair must be rejected even though the rear axle and the vehicle-wide base pair are both safe");
 }
}
