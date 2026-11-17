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
  assertEquals(30.0, r.marineSteerDegrees, "marineSteerDegrees has no flat fallback of its own - it inherits the resolved maxSteerDegrees (30deg here)");
  assertEquals(0.5, r.hullFillFactor); assertEquals(400.0, r.waterDrag); assertEquals(6000.0, r.propellerForce);
  assertEquals(0.0, r.angularWaterDrag, "angularWaterDrag's fallback is 0 - no rotational damping term unless configured");
  assertEquals(0.0, r.antiRollStiffness, "antiRollStiffness's fallback is 0 - no axle anti-roll coupling unless configured");
  assertEquals(FlightForces.DEFAULT_STALL_ANGLE_DEGREES, r.stallAngleDegrees, "stallAngleDegrees falls back to FlightForces's own previously-hardcoded 15deg");
  assertEquals(FlightForces.DEFAULT_LIFT_SLOPE, r.liftSlope, 1e-9, "liftSlope falls back to FlightForces's own previously-hardcoded 2*pi");
  assertEquals(FlightForces.DEFAULT_CONTROL_SURFACE_SLOPE, r.controlSurfaceSlope, 1e-9, "controlSurfaceSlope falls back to the same previously-hardcoded 2*pi");
  assertEquals(1.0, r.aileronAuthorityScale, "aileronAuthorityScale's fallback is unit scaling - no change from prior behavior");
  assertEquals(1.0, r.elevatorAuthorityScale, "elevatorAuthorityScale's fallback is unit scaling - no change from prior behavior");
  assertEquals(1.0, r.rudderAuthorityScale, "rudderAuthorityScale's fallback is unit scaling - no change from prior behavior");
  assertEquals(20.0, r.cyclicLimitDegrees, "cyclicLimitDegrees' fallback matches RotorForces' own prior hardcoded 20-degree clamp");
  assertEquals(1.0, r.cyclicAuthorityScale, "cyclicAuthorityScale's fallback is unit scaling - no change from prior behavior");
  assertEquals(1.0, r.yawAuthorityScale, "yawAuthorityScale's fallback is unit scaling - no change from prior behavior");
  assertEquals(0.0, r.rotorAngularDampingCoefficient, "rotorAngularDampingCoefficient's fallback is 0 - no angular-rate damping term at all unless configured (MINECRAFT-537 zero/off parity)");
  assertEquals(0.0, r.fixedWingAngularDampingCoefficient, "fixedWingAngularDampingCoefficient's fallback is 0 - no angular-rate damping term at all unless configured (MINECRAFT-542 zero/off parity), a wholly separate field from rotorAngularDampingCoefficient");
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
  assertNotNull(r.marineSteerDegrees);
  assertNotNull(r.hullFillFactor); assertNotNull(r.waterDrag); assertNotNull(r.propellerForce);
  assertNotNull(r.angularWaterDrag);
  assertNotNull(r.antiRollStiffness);
  assertNotNull(r.stallAngleDegrees); assertNotNull(r.liftSlope); assertNotNull(r.controlSurfaceSlope);
  assertNotNull(r.aileronAuthorityScale); assertNotNull(r.elevatorAuthorityScale); assertNotNull(r.rudderAuthorityScale);
  assertNotNull(r.cyclicLimitDegrees); assertNotNull(r.cyclicAuthorityScale); assertNotNull(r.yawAuthorityScale);
  assertNotNull(r.rotorAngularDampingCoefficient);
  assertNotNull(r.fixedWingAngularDampingCoefficient);
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

 // MINECRAFT-554: rotorAngularDampingCoefficientPitch/Roll/Yaw - optional per-axis overrides of
 // rotorAngularDampingCoefficient above, same [0, MAX_ANGULAR_DAMPING_COEFFICIENT] bound, same
 // "no FALLBACK entry, inherits the sibling resolved field instead" shape
 // suspensionDampingRatioFront/Rear/Compression/Rebound already establish.

 @Test void rotorAngularDampingCoefficientPerAxisOutOfRangeFailsValidation() {
  var negativePitch = new Overrides.Tuning(); negativePitch.rotorAngularDampingCoefficientPitch = -0.1;
  assertThrows(IllegalArgumentException.class, negativePitch::validate);
  var tooLargeRoll = new Overrides.Tuning(); tooLargeRoll.rotorAngularDampingCoefficientRoll = RotorForces.MAX_ANGULAR_DAMPING_COEFFICIENT + 0.01;
  assertThrows(IllegalArgumentException.class, tooLargeRoll::validate);
  var nonFiniteYaw = new Overrides.Tuning(); nonFiniteYaw.rotorAngularDampingCoefficientYaw = Double.NaN;
  assertThrows(IllegalArgumentException.class, nonFiniteYaw::validate);
  var zeroPitch = new Overrides.Tuning(); zeroPitch.rotorAngularDampingCoefficientPitch = 0.0; // explicit zero/off is allowed
  assertDoesNotThrow(zeroPitch::validate);
  var atCapRoll = new Overrides.Tuning(); atCapRoll.rotorAngularDampingCoefficientRoll = RotorForces.MAX_ANGULAR_DAMPING_COEFFICIENT; // the cap itself is allowed
  assertDoesNotThrow(atCapRoll::validate);
  var inRangeYaw = new Overrides.Tuning(); inRangeYaw.rotorAngularDampingCoefficientYaw = 5.0;
  assertDoesNotThrow(inRangeYaw::validate);
 }

 @Test void rotorAngularDampingCoefficientPerAxisStaysNullWhenUnconfigured() {
  // Unlike rotorAngularDampingCoefficient itself (which falls back to 0.0), the per-axis
  // overrides have no FALLBACK entry at all - Overrides.resolved() leaves them null so
  // PhysicsBridge.stepRotor knows to inherit the vehicle's own resolved rotorAngularDampingCoefficient
  // instead of a second, independently-defaulted value.
  var r = Overrides.resolved("packid:not_configured");
  assertNull(r.rotorAngularDampingCoefficientPitch);
  assertNull(r.rotorAngularDampingCoefficientRoll);
  assertNull(r.rotorAngularDampingCoefficientYaw);
  assertNull(Overrides.defaultTuning().rotorAngularDampingCoefficientPitch);
  assertNull(Overrides.defaultTuning().rotorAngularDampingCoefficientRoll);
  assertNull(Overrides.defaultTuning().rotorAngularDampingCoefficientYaw);
 }

 @Test void rotorAngularDampingCoefficientPerAxisParticipatesInTheFullPrecedenceChainIndependently() {
  try {
   // Mixed-axis case (ticket requirement): only pitch and yaw configured, roll left unset -
   // each axis resolves independently, roll stays null (inherits the base scalar downstream).
   var override = new Overrides.Tuning();
   override.rotorAngularDampingCoefficient = 4.5;
   override.rotorAngularDampingCoefficientPitch = 1.0; override.rotorAngularDampingCoefficientYaw = 7.0;
   Overrides.setForTest(java.util.Map.of("packid:mixed_axis_rotor", override));
   var resolved = Overrides.resolved("packid:mixed_axis_rotor");
   assertEquals(1.0, resolved.rotorAngularDampingCoefficientPitch, "an explicit per-axis override must take precedence");
   assertNull(resolved.rotorAngularDampingCoefficientRoll, "an unset axis must stay null, not fall back to a second hardcoded default");
   assertEquals(7.0, resolved.rotorAngularDampingCoefficientYaw);
   assertEquals(4.5, resolved.rotorAngularDampingCoefficient, "the base scalar itself is unaffected by the per-axis overrides");
  } finally {
   Overrides.setForTest(java.util.Map.of());
  }
 }

 @Test void rotorAngularDampingCoefficientPerAxisInheritsThroughAnExtendsTemplate() {
  try {
   var template = new Overrides.Tuning(); template.rotorAngularDampingCoefficientRoll = 2.0;
   var child = new Overrides.Tuning(); child.extendsTemplate = "per-axis-damping-base"; // sets no fields of its own
   Overrides.setForTest(java.util.Map.of("per-axis-damping-base", template), java.util.Map.of("packid:inherited_axis_damping", child), java.util.Map.of());
   assertEquals(2.0, Overrides.resolved("packid:inherited_axis_damping").rotorAngularDampingCoefficientRoll,
     "a field the child never set must inherit the template's value, same as every other tuning field");
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 @Test void rotorAngularDampingCoefficientPerAxisIsRejectedOnAPartOverride() {
  var pitchPart = new Overrides.Tuning(); pitchPart.rotorAngularDampingCoefficientPitch = 1.5;
  assertThrows(IllegalArgumentException.class, pitchPart::validateSupportedForPart,
    "this is a vehicle-only concept - a rotorcraft part has no own angular-damping consumer");
  var rollPart = new Overrides.Tuning(); rollPart.rotorAngularDampingCoefficientRoll = 1.5;
  assertThrows(IllegalArgumentException.class, rollPart::validateSupportedForPart);
  var yawPart = new Overrides.Tuning(); yawPart.rotorAngularDampingCoefficientYaw = 1.5;
  assertThrows(IllegalArgumentException.class, yawPart::validateSupportedForPart);
 }

 @Test void validThenInvalidPerAxisRotorAngularDampingReloadAppliesThenRetainsThePriorGoodOverride() {
  try {
   var goodHelicopter = new Overrides.Tuning(); goodHelicopter.rotorAngularDampingCoefficientYaw = 3.0;
   var goodResult = Overrides.apply(() -> new Overrides.Config(java.util.Map.of(),
     java.util.Map.of("examplepack:reloadable_axis_helicopter", goodHelicopter), java.util.Map.of()));
   assertTrue(goodResult.success(), "a valid rotorAngularDampingCoefficientYaw reload must apply cleanly");
   assertEquals(3.0, Overrides.resolved("examplepack:reloadable_axis_helicopter").rotorAngularDampingCoefficientYaw);

   var invalidHelicopter = new Overrides.Tuning(); invalidHelicopter.rotorAngularDampingCoefficientYaw = -1.0; // out of range
   var badResult = Overrides.apply(() -> new Overrides.Config(java.util.Map.of(),
     java.util.Map.of("examplepack:reloadable_axis_helicopter", invalidHelicopter), java.util.Map.of()));
   assertFalse(badResult.success(), "a negative rotorAngularDampingCoefficientYaw reload must be rejected, not silently applied");
   assertEquals(3.0, Overrides.resolved("examplepack:reloadable_axis_helicopter").rotorAngularDampingCoefficientYaw,
     "a rejected reload must retain the PRIOR GOOD value, not the invalid value or a silent null");
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 // MINECRAFT-542: fixedWingAngularDampingCoefficient - bounded, optional, fixed-wing-only
 // angular-rate damping torque, reusing M537's dissipative-drag formula/no-reversal clamp but a
 // wholly separate field from rotorAngularDampingCoefficient above (deliberately never shared).

 @Test void fixedWingAngularDampingCoefficientOutOfRangeFailsValidation() {
  var negative = new Overrides.Tuning(); negative.fixedWingAngularDampingCoefficient = -0.1;
  assertThrows(IllegalArgumentException.class, negative::validate);
  var tooLarge = new Overrides.Tuning(); tooLarge.fixedWingAngularDampingCoefficient = FlightForces.MAX_FIXED_WING_ANGULAR_DAMPING_COEFFICIENT + 0.01;
  assertThrows(IllegalArgumentException.class, tooLarge::validate);
  var nonFinite = new Overrides.Tuning(); nonFinite.fixedWingAngularDampingCoefficient = Double.NaN;
  assertThrows(IllegalArgumentException.class, nonFinite::validate);
  var zero = new Overrides.Tuning(); zero.fixedWingAngularDampingCoefficient = 0.0; // explicit zero/off is allowed
  assertDoesNotThrow(zero::validate);
  var atCap = new Overrides.Tuning(); atCap.fixedWingAngularDampingCoefficient = FlightForces.MAX_FIXED_WING_ANGULAR_DAMPING_COEFFICIENT; // the cap itself is allowed
  assertDoesNotThrow(atCap::validate);
  var inRange = new Overrides.Tuning(); inRange.fixedWingAngularDampingCoefficient = 5.0;
  assertDoesNotThrow(inRange::validate);
 }

 @Test void fixedWingAngularDampingCoefficientParticipatesInTheFullPrecedenceChain() {
  try {
   var override = new Overrides.Tuning(); override.fixedWingAngularDampingCoefficient = 4.5;
   Overrides.setForTest(java.util.Map.of("packid:damped_fixed_wing_vehicle", override));
   assertEquals(4.5, Overrides.resolved("packid:damped_fixed_wing_vehicle").fixedWingAngularDampingCoefficient,
     "an explicit operator override must take precedence over the 0.0 fallback");
  } finally {
   Overrides.setForTest(java.util.Map.of());
  }
 }

 @Test void fixedWingAngularDampingCoefficientInheritsThroughAnExtendsTemplate() {
  try {
   var template = new Overrides.Tuning(); template.fixedWingAngularDampingCoefficient = 2.0;
   var child = new Overrides.Tuning(); child.extendsTemplate = "fixed-wing-damping-base"; // sets no fields of its own
   Overrides.setForTest(java.util.Map.of("fixed-wing-damping-base", template), java.util.Map.of("packid:inherited_fixed_wing_damping", child), java.util.Map.of());
   assertEquals(2.0, Overrides.resolved("packid:inherited_fixed_wing_damping").fixedWingAngularDampingCoefficient,
     "a field the child never set must inherit the template's value, same as every other tuning field");
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 @Test void fixedWingAngularDampingCoefficientIsRejectedOnAPartOverride() {
  var part = new Overrides.Tuning(); part.fixedWingAngularDampingCoefficient = 1.5;
  assertThrows(IllegalArgumentException.class, part::validateSupportedForPart,
    "this is a vehicle-only concept - a fixed-wing part has no own angular-damping consumer");
 }

 @Test void validThenInvalidFixedWingAngularDampingReloadAppliesThenRetainsThePriorGoodOverride() {
  // Same "invalid reload retains last-known-good" structure as rotorAngularDampingCoefficient
  // above - exercises the REAL Overrides.apply(Supplier<Config>) seam, not merely setForTest, so
  // a bad edit mid-session cannot silently corrupt an already-good fixed-wing damping
  // configuration.
  try {
   var goodAirplane = new Overrides.Tuning(); goodAirplane.fixedWingAngularDampingCoefficient = 3.0;
   var goodResult = Overrides.apply(() -> new Overrides.Config(java.util.Map.of(),
     java.util.Map.of("examplepack:reloadable_airplane", goodAirplane), java.util.Map.of()));
   assertTrue(goodResult.success(), "a valid fixedWingAngularDampingCoefficient reload must apply cleanly");
   assertEquals(3.0, Overrides.resolved("examplepack:reloadable_airplane").fixedWingAngularDampingCoefficient);

   var invalidAirplane = new Overrides.Tuning(); invalidAirplane.fixedWingAngularDampingCoefficient = -1.0; // out of range
   var badResult = Overrides.apply(() -> new Overrides.Config(java.util.Map.of(),
     java.util.Map.of("examplepack:reloadable_airplane", invalidAirplane), java.util.Map.of()));
   assertFalse(badResult.success(), "a negative fixedWingAngularDampingCoefficient reload must be rejected, not silently applied");
   assertEquals(3.0, Overrides.resolved("examplepack:reloadable_airplane").fixedWingAngularDampingCoefficient,
     "a rejected reload must retain the PRIOR GOOD fixedWingAngularDampingCoefficient, not the invalid value or the 0.0 fallback");
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

 // MINECRAFT-541: aileron/elevator/rudderAuthorityScale - bounded, independent per-axis
 // fixed-wing control authority, each a multiplier on top of the shared controlSurfaceSlope
 // above, consumed by PhysicsBridge.stepFlight.

 @Test void axisAuthorityScalesOutOfRangeFailValidation() {
  // Review correction (comment 54143): unlike cyclicAuthorityScale/yawAuthorityScale, 0.0 must
  // be explicitly VALID here (the ticket's own "test zero/unit/extreme valid values"
  // acceptance) - there is no other per-axis knob to zero out just one axis's authority.
  var negativeAileron = new Overrides.Tuning(); negativeAileron.aileronAuthorityScale = -1.0;
  assertThrows(IllegalArgumentException.class, negativeAileron::validate);
  var tooLargeAileron = new Overrides.Tuning(); tooLargeAileron.aileronAuthorityScale = 5.01;
  assertThrows(IllegalArgumentException.class, tooLargeAileron::validate);
  var zeroAileron = new Overrides.Tuning(); zeroAileron.aileronAuthorityScale = 0.0;
  assertDoesNotThrow(zeroAileron::validate, "0.0 (fully disabling just this one axis's authority) must be explicitly valid");
  var atCapAileron = new Overrides.Tuning(); atCapAileron.aileronAuthorityScale = 5.0;
  assertDoesNotThrow(atCapAileron::validate);
  var inRangeAileron = new Overrides.Tuning(); inRangeAileron.aileronAuthorityScale = 1.5;
  assertDoesNotThrow(inRangeAileron::validate);

  var negativeElevator = new Overrides.Tuning(); negativeElevator.elevatorAuthorityScale = -0.01;
  assertThrows(IllegalArgumentException.class, negativeElevator::validate);
  var tooLargeElevator = new Overrides.Tuning(); tooLargeElevator.elevatorAuthorityScale = 100.0;
  assertThrows(IllegalArgumentException.class, tooLargeElevator::validate);
  var zeroElevator = new Overrides.Tuning(); zeroElevator.elevatorAuthorityScale = 0.0;
  assertDoesNotThrow(zeroElevator::validate, "0.0 must be explicitly valid");
  var inRangeElevator = new Overrides.Tuning(); inRangeElevator.elevatorAuthorityScale = 0.5;
  assertDoesNotThrow(inRangeElevator::validate);

  var negativeRudder = new Overrides.Tuning(); negativeRudder.rudderAuthorityScale = -1.0;
  assertThrows(IllegalArgumentException.class, negativeRudder::validate);
  var tooLargeRudder = new Overrides.Tuning(); tooLargeRudder.rudderAuthorityScale = 5.001;
  assertThrows(IllegalArgumentException.class, tooLargeRudder::validate);
  var zeroRudder = new Overrides.Tuning(); zeroRudder.rudderAuthorityScale = 0.0;
  assertDoesNotThrow(zeroRudder::validate, "0.0 must be explicitly valid");
  var inRangeRudder = new Overrides.Tuning(); inRangeRudder.rudderAuthorityScale = 2.0;
  assertDoesNotThrow(inRangeRudder::validate);
 }

 @Test void axisAuthorityScalesParticipateInTheFullPrecedenceChainIndependently() {
  try {
   var override = new Overrides.Tuning();
   // Only aileronAuthorityScale is overridden - the others must still resolve to their own
   // 1.0 fallback, not pick up this value or any other axis' override (ticket acceptance:
   // "changing one axis does not scale the others").
   override.aileronAuthorityScale = 2.0;
   Overrides.setForTest(java.util.Map.of("packid:glider", override));
   var r = Overrides.resolved("packid:glider");
   assertEquals(2.0, r.aileronAuthorityScale, "an explicit operator override must take precedence over the 1.0 fallback");
   assertEquals(1.0, r.elevatorAuthorityScale, "an unrelated axis override must not change elevatorAuthorityScale's own fallback");
   assertEquals(1.0, r.rudderAuthorityScale, "an unrelated axis override must not change rudderAuthorityScale's own fallback");
  } finally {
   Overrides.setForTest(java.util.Map.of());
  }
 }

 @Test void axisAuthorityScalesAreRejectedOnAPartOverride() {
  var aileron = new Overrides.Tuning(); aileron.aileronAuthorityScale = 1.5;
  assertThrows(IllegalArgumentException.class, aileron::validateSupportedForPart);
  var elevator = new Overrides.Tuning(); elevator.elevatorAuthorityScale = 1.5;
  assertThrows(IllegalArgumentException.class, elevator::validateSupportedForPart);
  var rudder = new Overrides.Tuning(); rudder.rudderAuthorityScale = 1.5;
  assertThrows(IllegalArgumentException.class, rudder::validateSupportedForPart);
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

 // MINECRAFT-544: marineSteerDegrees - an independent, bounded water-propeller steering limit
 // from the ground maxSteerDegrees, see BuoyantForces.apply. Unlike brakeBiasFront/driveBiasFront
 // (which stay null when unconfigured), marineSteerDegrees falls through to the SAME entry's own
 // resolved maxSteerDegrees rather than staying null or using a second flat constant.

 @Test void marineSteerDegreesOutOfRangeFailsValidation() {
  var negative = new Overrides.Tuning(); negative.marineSteerDegrees = -0.01;
  assertThrows(IllegalArgumentException.class, negative::validate);
  var tooLarge = new Overrides.Tuning(); tooLarge.marineSteerDegrees = 60.01;
  assertThrows(IllegalArgumentException.class, tooLarge::validate);
  var inRange = new Overrides.Tuning(); inRange.marineSteerDegrees = 45.0;
  assertDoesNotThrow(inRange::validate);
  var atCeiling = new Overrides.Tuning(); atCeiling.marineSteerDegrees = 60.0;
  assertDoesNotThrow(atCeiling::validate);
 }

 @Test void marineSteerDegreesZeroIsAValidInclusiveExtremeUnlikeMaxSteerDegreesItself() {
  // Review correction: UNLIKE maxSteerDegrees (strictly positive), 0.0 must be an ACCEPTED
  // marineSteerDegrees value - a vehicle whose propellers physically cannot swivel at all
  // (straight-only thrust) is a real, sensible configuration, not a degenerate one.
  var zeroMarine = new Overrides.Tuning(); zeroMarine.marineSteerDegrees = 0.0;
  assertDoesNotThrow(zeroMarine::validate);
  // And it must stay genuinely independent: a zero marine limit must not force (or require)
  // maxSteerDegrees to also be zero - wheel steering can stay fully nonzero at the same time.
  try {
   var override = new Overrides.Tuning(); override.maxSteerDegrees = 35.0; override.marineSteerDegrees = 0.0;
   Overrides.setForTest(java.util.Map.of("packid:straight_thrust_steerable_wheels_vehicle", override));
   var r = Overrides.resolved("packid:straight_thrust_steerable_wheels_vehicle");
   assertEquals(35.0, r.maxSteerDegrees, "wheel steering must stay fully nonzero");
   assertEquals(0.0, r.marineSteerDegrees, "the explicit zero marine limit must resolve exactly, not get coerced to something nonzero");
  } finally {
   Overrides.setForTest(java.util.Map.of());
  }
 }

 @Test void marineSteerDegreesInheritsTheResolvedMaxSteerDegreesWhenUnconfigured() {
  assertEquals(30.0, Overrides.resolved("packid:not_configured").marineSteerDegrees,
    "unconfigured marineSteerDegrees must inherit the resolved (fallback) maxSteerDegrees, not a second hardcoded constant");
  try {
   var override = new Overrides.Tuning(); override.maxSteerDegrees = 42.0; // no marineSteerDegrees set
   Overrides.setForTest(java.util.Map.of("packid:custom_steer_vehicle", override));
   assertEquals(42.0, Overrides.resolved("packid:custom_steer_vehicle").marineSteerDegrees,
     "with maxSteerDegrees itself overridden but marineSteerDegrees left unset, marineSteerDegrees must inherit that SAME resolved maxSteerDegrees, not the flat 30deg fallback");
  } finally {
   Overrides.setForTest(java.util.Map.of());
  }
 }

 @Test void marineSteerDegreesExplicitOverrideWinsOverTheInheritedMaxSteerDegrees() {
  try {
   var override = new Overrides.Tuning(); override.maxSteerDegrees = 42.0; override.marineSteerDegrees = 15.0;
   Overrides.setForTest(java.util.Map.of("packid:independent_steer_vehicle", override));
   var r = Overrides.resolved("packid:independent_steer_vehicle");
   assertEquals(42.0, r.maxSteerDegrees, "maxSteerDegrees (ground) must stay exactly what was configured");
   assertEquals(15.0, r.marineSteerDegrees, "an explicit marineSteerDegrees must win over inheriting maxSteerDegrees - the two limits are independent");
  } finally {
   Overrides.setForTest(java.util.Map.of());
  }
 }

 @Test void marineSteerDegreesParticipatesInTheFullPrecedenceChain() {
  var packMetadata = new Overrides.Tuning(); packMetadata.marineSteerDegrees = 25.0;
  assertEquals(25.0, Overrides.resolved("packid:not_configured", packMetadata, null, null).marineSteerDegrees,
    "with no operator override, pack DIV metadata must still be used over inheriting maxSteerDegrees");
 }

 @Test void marineSteerDegreesIsRejectedOnAPartOverride() {
  var part = new Overrides.Tuning(); part.marineSteerDegrees = 20.0;
  assertThrows(IllegalArgumentException.class, part::validateSupportedForPart);
 }

 @Test void marineSteerDegreesIsInheritedThroughAnExtendsTemplate() {
  try {
   var template = new Overrides.Tuning(); template.marineSteerDegrees = 18.0;
   var entry = new Overrides.Tuning(); entry.extendsTemplate = "steer_template";
   Overrides.setForTest(java.util.Map.of("steer_template", template), java.util.Map.of("packid:extends_steer_vehicle", entry), java.util.Map.of());
   assertEquals(18.0, Overrides.resolved("packid:extends_steer_vehicle").marineSteerDegrees);
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
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

 @Test void ackermannSteeringHasNoFallbackAndStaysNullWhenNeverConfigured() {
  // Same "stays null, not a default value" shape as axleDriveForceSharing above -
  // AckermannSteeringDistribution's own caller treats null/false as "disabled uniform steer".
  assertNull(Overrides.resolved("packid:not_configured").ackermannSteering);
  assertNull(Overrides.defaultTuning().ackermannSteering);
 }

 @Test void ackermannSteeringParticipatesInTheFullPrecedenceChain() {
  try {
   var override = new Overrides.Tuning(); override.ackermannSteering = true;
   Overrides.setForTest(java.util.Map.of("packid:ackermann_vehicle", override));
   assertEquals(true, Overrides.resolved("packid:ackermann_vehicle").ackermannSteering,
     "an explicit operator override must be used instead of staying null");
  } finally {
   Overrides.setForTest(java.util.Map.of());
  }
  var packMetadata = new Overrides.Tuning(); packMetadata.ackermannSteering = true;
  assertEquals(true, Overrides.resolved("packid:not_configured", packMetadata, null, null).ackermannSteering,
    "with no operator override, pack DIV metadata must still be used over staying null");
 }

 @Test void ackermannSteeringIsRejectedOnAPartOverride() {
  var part = new Overrides.Tuning(); part.ackermannSteering = true;
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

 // MINECRAFT-552: installed-tire lateral-grip overrides - lateralGripMultiplier is now a
 // supported part field, same exact shape as friction's own MINECRAFT-529 coverage above
 // (bounds, extends-inheritance, vehicle-fallthrough precedence, independent per-wheel
 // resolution, valid/invalid reload retention), applied to only the lateral friction-ellipse
 // semi-axis, never the longitudinal (drive/brake) one - a tire can override this without
 // touching friction/rollingResistance.

 @Test void partEntrySettingLateralGripMultiplierPassesTheSupportedFieldsCheck() {
  var part = new Overrides.Tuning(); part.lateralGripMultiplier = 0.4;
  assertDoesNotThrow(part::validateSupportedForPart,
    "MINECRAFT-552: an installed tire part's own lateralGripMultiplier override must be an accepted part field");
 }

 @Test void partEntrySettingOutOfRangeLateralGripMultiplierStillFailsPlainValidation() {
  var tooHigh = new Overrides.Tuning(); tooHigh.lateralGripMultiplier = 1.1;
  assertThrows(IllegalArgumentException.class, tooHigh::validate);
  var tooLow = new Overrides.Tuning(); tooLow.lateralGripMultiplier = 0.0;
  assertThrows(IllegalArgumentException.class, tooLow::validate);
 }

 @Test void partEntrySettingNonFiniteLateralGripMultiplierStillFailsPlainValidation() {
  // Self-review finding: the ticket explicitly requires non-finite coverage, not just finite
  // out-of-range values - validate()'s own lateralGripMultiplier check is
  // Double.isFinite(...) && >0 && <=1, so NaN and either infinity must all fail loudly too.
  var nan = new Overrides.Tuning(); nan.lateralGripMultiplier = Double.NaN;
  assertThrows(IllegalArgumentException.class, nan::validate, "a NaN lateralGripMultiplier must fail validate()");
  var positiveInfinity = new Overrides.Tuning(); positiveInfinity.lateralGripMultiplier = Double.POSITIVE_INFINITY;
  assertThrows(IllegalArgumentException.class, positiveInfinity::validate, "a positive-infinity lateralGripMultiplier must fail validate()");
  var negativeInfinity = new Overrides.Tuning(); negativeInfinity.lateralGripMultiplier = Double.NEGATIVE_INFINITY;
  assertThrows(IllegalArgumentException.class, negativeInfinity::validate, "a negative-infinity lateralGripMultiplier must fail validate()");
 }

 @Test void partLateralGripMultiplierOverrideInheritsThroughAnExtendsTemplate() {
  try {
   var template = new Overrides.Tuning(); template.lateralGripMultiplier = 0.55;
   var part = new Overrides.Tuning(); part.extendsTemplate = "tire-base";
   Overrides.setForTest(java.util.Map.of("tire-base", template), java.util.Map.of(), java.util.Map.of("packid:inherited_tire", part));
   var merged = Overrides.effectivePartOverride("packid:inherited_tire");
   assertEquals(0.55, merged.lateralGripMultiplier, "a part inheriting lateralGripMultiplier from a template must fold it in exactly like any other field");
   assertDoesNotThrow(merged::validateSupportedForPart);
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 @Test void tirePartLateralGripMultiplierOverrideWinsOverTheVehicleResolvedValue() {
  try {
   var part = new Overrides.Tuning(); part.lateralGripMultiplier = 0.3;
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of("examplepack:slick_tire", part));
   assertEquals(0.3, PhysicsBridge.tirePartLateralGripMultiplier("examplepack:slick_tire", 1.0),
     "an explicit part-level lateralGripMultiplier override must win outright (replace, not multiply) over the vehicle's own resolved value");
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 @Test void tirePartLateralGripMultiplierFallsThroughToTheVehicleResolvedValueWhenNoPartOverrideExists() {
  assertEquals(1.0, PhysicsBridge.tirePartLateralGripMultiplier("packid:ordinary_tire_with_no_override", 1.0),
    "no parts entry at all for this tire key must be a true no-op, reproducing the vehicle's resolved lateralGripMultiplier unchanged");
 }

 @Test void tirePartLateralGripMultiplierResolvesIndependentlyPerWheelKeySoDifferentInstalledTiresGetDifferentGrip() {
  try {
   var slick = new Overrides.Tuning(); slick.lateralGripMultiplier = 0.2;
   var grippy = new Overrides.Tuning(); grippy.lateralGripMultiplier = 1.0;
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(),
     java.util.Map.of("examplepack:slick_tire", slick, "examplepack:grippy_tire", grippy));
   assertEquals(0.2, PhysicsBridge.tirePartLateralGripMultiplier("examplepack:slick_tire", 0.7));
   assertEquals(1.0, PhysicsBridge.tirePartLateralGripMultiplier("examplepack:grippy_tire", 0.7));
   assertEquals(0.7, PhysicsBridge.tirePartLateralGripMultiplier("examplepack:unconfigured_tire", 0.7),
     "a third, unconfigured tire key on the same vehicle must stay at the vehicle's own resolved value - independent wheel identities");
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 @Test void tirePartLateralGripMultiplierIsIndependentOfTirePartFrictionAndRollingResistanceOnTheSamePart() {
  try {
   var part = new Overrides.Tuning(); part.lateralGripMultiplier = 0.25; // friction/rollingResistance left unset
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of("examplepack:only_lateral_grip_tire", part));
   assertEquals(0.25, PhysicsBridge.tirePartLateralGripMultiplier("examplepack:only_lateral_grip_tire", 1.0));
   assertEquals(1.0, PhysicsBridge.tirePartFriction("examplepack:only_lateral_grip_tire", 1.0),
     "a part overriding only lateralGripMultiplier must leave friction completely untouched - independent fields");
   assertEquals(1.0, PhysicsBridge.tirePartRollingResistance("examplepack:only_lateral_grip_tire", 1.0),
     "a part overriding only lateralGripMultiplier must leave rollingResistance completely untouched - independent fields");
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 @Test void validThenInvalidPartLateralGripMultiplierReloadAppliesThenRetainsThePriorGoodTireOverride() {
  try {
   var goodTire = new Overrides.Tuning(); goodTire.lateralGripMultiplier = 0.65;
   var goodResult = Overrides.apply(() -> new Overrides.Config(java.util.Map.of(), java.util.Map.of(),
     java.util.Map.of("examplepack:reloadable_tire", goodTire)));
   assertTrue(goodResult.success(), "a valid parts.<tire>.lateralGripMultiplier reload must apply cleanly");
   assertEquals(0.65, PhysicsBridge.tirePartLateralGripMultiplier("examplepack:reloadable_tire", 1.0),
     "PhysicsBridge.tirePartLateralGripMultiplier must observe the newly-applied valid part lateralGripMultiplier override");

   var invalidTire = new Overrides.Tuning(); invalidTire.lateralGripMultiplier = 0.0; // out of range: must be >0
   var badResult = Overrides.apply(() -> new Overrides.Config(java.util.Map.of(), java.util.Map.of(),
     java.util.Map.of("examplepack:reloadable_tire", invalidTire)));
   assertFalse(badResult.success(), "an out-of-range part lateralGripMultiplier (0.0) reload must be rejected, not silently applied");
   assertEquals(0.65, PhysicsBridge.tirePartLateralGripMultiplier("examplepack:reloadable_tire", 1.0),
     "a rejected reload must retain the PRIOR GOOD part lateralGripMultiplier override, not the invalid value or the vehicle fallback");

   var tooHighTire = new Overrides.Tuning(); tooHighTire.lateralGripMultiplier = 1.5; // out of range: must be <=1
   var badResult2 = Overrides.apply(() -> new Overrides.Config(java.util.Map.of(), java.util.Map.of(),
     java.util.Map.of("examplepack:reloadable_tire", tooHighTire)));
   assertFalse(badResult2.success(), "an out-of-range part lateralGripMultiplier (1.5) reload must also be rejected");
   assertEquals(0.65, PhysicsBridge.tirePartLateralGripMultiplier("examplepack:reloadable_tire", 1.0),
     "a second rejected reload must still retain the same prior good part lateralGripMultiplier override");

   // Self-review finding: the ticket explicitly requires non-finite reload coverage too, not
   // just finite out-of-range values - Tuning.validate()'s own lateralGripMultiplier check uses
   // Double.isFinite(...) (see that bound's own doc), so both NaN and an infinity must also be
   // rejected while retaining the same prior good override.
   var nanTire = new Overrides.Tuning(); nanTire.lateralGripMultiplier = Double.NaN;
   var badResult3 = Overrides.apply(() -> new Overrides.Config(java.util.Map.of(), java.util.Map.of(),
     java.util.Map.of("examplepack:reloadable_tire", nanTire)));
   assertFalse(badResult3.success(), "a NaN part lateralGripMultiplier reload must be rejected, not silently applied");
   assertEquals(0.65, PhysicsBridge.tirePartLateralGripMultiplier("examplepack:reloadable_tire", 1.0),
     "a NaN-rejected reload must still retain the same prior good part lateralGripMultiplier override");

   var infiniteTire = new Overrides.Tuning(); infiniteTire.lateralGripMultiplier = Double.POSITIVE_INFINITY;
   var badResult4 = Overrides.apply(() -> new Overrides.Config(java.util.Map.of(), java.util.Map.of(),
     java.util.Map.of("examplepack:reloadable_tire", infiniteTire)));
   assertFalse(badResult4.success(), "a positive-infinity part lateralGripMultiplier reload must be rejected, not silently applied");
   assertEquals(0.65, PhysicsBridge.tirePartLateralGripMultiplier("examplepack:reloadable_tire", 1.0),
     "an infinity-rejected reload must still retain the same prior good part lateralGripMultiplier override");
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 // Review finding (PR #51/Jira comment 54581): swappingTheResolvedTireLateralGripChangesTheComposedRatio
 // in MultiContactFrictionSolverTirePartLateralGripTest only exercises local arithmetic on two
 // already-resolved doubles - it never changes live Overrides state, so it would pass even if
 // real installed-tire replacement/removal kept returning a stale override. This regression
 // drives the REAL active-config seam (Overrides.apply, the same one a live config reload uses)
 // for one canonical installed-part key: apply a part entry, observe its resolved lateral
 // multiplier through PhysicsBridge.tirePartLateralGripMultiplier; replace that same key's entry
 // with a different value and observe the NEW value take effect; then remove the entry entirely
 // and confirm the exact vehicle-level fallback is restored, not the removed value or a stale
 // cache - the ticket's explicit "canonical-key resolution/inheritance, removal/replacement"
 // coverage requirement, proven through live state rather than local arithmetic.
 @Test void tirePartLateralGripMultiplierObservesLiveReplacementAndRestoresVehicleFallbackOnRemoval() {
  try {
   double vehicleLateralGrip = 0.6;
   var initial = new Overrides.Tuning(); initial.lateralGripMultiplier = 0.3;
   var applyInitial = Overrides.apply(() -> new Overrides.Config(java.util.Map.of(), java.util.Map.of(),
     java.util.Map.of("examplepack:canonical_tire", initial)));
   assertTrue(applyInitial.success(), "the initial parts.<tire>.lateralGripMultiplier config must apply cleanly");
   assertEquals(0.3, PhysicsBridge.tirePartLateralGripMultiplier("examplepack:canonical_tire", vehicleLateralGrip), 0,
     "the installed tire's resolved lateralGripMultiplier must observe the just-applied part override");

   var replacement = new Overrides.Tuning(); replacement.lateralGripMultiplier = 0.9;
   var applyReplacement = Overrides.apply(() -> new Overrides.Config(java.util.Map.of(), java.util.Map.of(),
     java.util.Map.of("examplepack:canonical_tire", replacement)));
   assertTrue(applyReplacement.success(), "replacing the same canonical tire key's part entry must apply cleanly");
   assertEquals(0.9, PhysicsBridge.tirePartLateralGripMultiplier("examplepack:canonical_tire", vehicleLateralGrip), 0,
     "replacing the part entry for the SAME canonical key must be observed as the new resolved value, not the stale prior override");

   var applyRemoval = Overrides.apply(() -> new Overrides.Config(java.util.Map.of(), java.util.Map.of(), java.util.Map.of()));
   assertTrue(applyRemoval.success(), "removing the tire's part entry entirely must itself apply cleanly");
   assertEquals(vehicleLateralGrip, PhysicsBridge.tirePartLateralGripMultiplier("examplepack:canonical_tire", vehicleLateralGrip), 0,
     "removing the part entry entirely must restore the exact vehicle-level fallback, not the removed override or a stale cache");
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 @Test void nonUnitVehicleLateralGripMultiplierFallsThroughUnchangedWhenNoPartOverrideExists() {
  double vehicleLateralGrip = 0.6;
  double resolved = PhysicsBridge.tirePartLateralGripMultiplier("packid:no_override_tire", vehicleLateralGrip);
  assertEquals(0.6, resolved, 0, "with no part override, the resolved value must equal the configured vehicle baseline exactly");
  double ratio = resolved / vehicleLateralGrip; // the exact stepGround composition
  assertEquals(1.0, ratio, 0, "a configured, non-unit vehicle lateralGripMultiplier with no part override must compose to the exact no-op ratio 1.0");
 }

 @Test void nonUnitVehicleLateralGripMultiplierIsReplacedOutrightByAPartOverrideInTheComposedRatio() {
  try {
   var part = new Overrides.Tuning(); part.lateralGripMultiplier = 0.9;
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of("examplepack:overridden_tire", part));
   double vehicleLateralGrip = 0.6;
   double resolved = PhysicsBridge.tirePartLateralGripMultiplier("examplepack:overridden_tire", vehicleLateralGrip);
   assertEquals(0.9, resolved, 0, "a part override must replace the vehicle's own non-unit baseline outright, not multiply/divide against it");
   double ratio = resolved / vehicleLateralGrip; // the exact stepGround composition
   assertEquals(1.5, ratio, 1e-12, "the composed ratio must be part/vehicle=1.5, which stepGround then multiplies back against the shared lateralGripMultiplier parameter");
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

 // MINECRAFT-541 self-review: same "invalid reload retaining last-good values" regression
 // already covered for stallAngleDegrees/liftSlope/controlSurfaceSlope above, for the new
 // aileron/elevator/rudderAuthorityScale fields. Also covers live removal: the second reload
 // below OMITS the vehicle entirely (not merely setting an invalid value), confirming it falls
 // all the way back to the flat 1.0 FALLBACK rather than retaining the first reload's values.

 @Test void invalidReloadRetainsThePriorGoodAxisAuthorityScalesAndRemovalFallsBackToDefault() {
  try {
   var good = new Overrides.Tuning();
   good.aileronAuthorityScale = 1.3; good.elevatorAuthorityScale = 0.9; good.rudderAuthorityScale = 1.1;
   var goodResult = Overrides.apply(() -> new Overrides.Config(java.util.Map.of(), java.util.Map.of("packid:glider4", good), java.util.Map.of()));
   assertTrue(goodResult.success(), "the known-good config itself must apply cleanly");
   assertEquals(1.3, Overrides.resolved("packid:glider4").aileronAuthorityScale, 1e-9);
   assertEquals(0.9, Overrides.resolved("packid:glider4").elevatorAuthorityScale, 1e-9);
   assertEquals(1.1, Overrides.resolved("packid:glider4").rudderAuthorityScale, 1e-9);

   var invalid = new Overrides.Tuning();
   // 0.0 is now explicitly VALID (review correction 54143), so use genuinely out-of-range
   // values here instead - negative and above the 5.0 cap.
   invalid.aileronAuthorityScale = -1.0; invalid.elevatorAuthorityScale = 999.0; invalid.rudderAuthorityScale = -2.0;
   var badResult = Overrides.apply(() -> new Overrides.Config(java.util.Map.of(), java.util.Map.of("packid:glider4", invalid), java.util.Map.of()));
   assertFalse(badResult.success(), "an out-of-range reload for these fields must be rejected, not silently applied");

   assertEquals(1.3, Overrides.resolved("packid:glider4").aileronAuthorityScale, 1e-9,
     "a rejected reload must retain the PRIOR GOOD value, not the invalid one or the flat fallback");
   assertEquals(0.9, Overrides.resolved("packid:glider4").elevatorAuthorityScale, 1e-9,
     "a rejected reload must retain the PRIOR GOOD value, not the invalid one or the flat fallback");
   assertEquals(1.1, Overrides.resolved("packid:glider4").rudderAuthorityScale, 1e-9,
     "a rejected reload must retain the PRIOR GOOD value, not the invalid one or the flat fallback");

   var removalResult = Overrides.apply(() -> new Overrides.Config(java.util.Map.of(), java.util.Map.of(), java.util.Map.of()));
   assertTrue(removalResult.success(), "a reload that simply omits the vehicle entirely must itself apply cleanly");
   assertEquals(1.0, Overrides.resolved("packid:glider4").aileronAuthorityScale, 1e-9,
     "removing the vehicle's entry entirely must fall back to the flat 1.0 default, not retain the prior good override");
   assertEquals(1.0, Overrides.resolved("packid:glider4").elevatorAuthorityScale, 1e-9,
     "removing the vehicle's entry entirely must fall back to the flat 1.0 default, not retain the prior good override");
   assertEquals(1.0, Overrides.resolved("packid:glider4").rudderAuthorityScale, 1e-9,
     "removing the vehicle's entry entirely must fall back to the flat 1.0 default, not retain the prior good override");
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

 // MINECRAFT-551: suspensionDampingRatioCompression/Rebound - vehicle-wide, direction-selected
 // (by the suspension anchor's own signed velocity, see TireForces.suspension's 5-arg overload)
 // overrides of suspensionDampingRatio. Same [strictly-positive, 2.0] bound as the base field, no
 // FALLBACK entry (unset direction inherits the per-wheel value AxleSuspensionDistribution already
 // resolved - see PhysicsBridge.directionalDampingRatio), checked against every axle's own
 // effective spring multiplier since (unlike suspensionDampingRatioFront/Rear) these two are
 // deliberately vehicle-wide once configured.

 @Test void directionalDampingRatioFieldsOutOfRangeFailValidation() {
  var zeroCompression = new Overrides.Tuning(); zeroCompression.suspensionDampingRatioCompression = 0.0;
  assertThrows(IllegalArgumentException.class, zeroCompression::validate);
  var negativeRebound = new Overrides.Tuning(); negativeRebound.suspensionDampingRatioRebound = -0.5;
  assertThrows(IllegalArgumentException.class, negativeRebound::validate);
  var tooLargeCompression = new Overrides.Tuning(); tooLargeCompression.suspensionDampingRatioCompression = 2.01;
  assertThrows(IllegalArgumentException.class, tooLargeCompression::validate);
  var nanRebound = new Overrides.Tuning(); nanRebound.suspensionDampingRatioRebound = Double.NaN;
  assertThrows(IllegalArgumentException.class, nanRebound::validate);
  var validCompression = new Overrides.Tuning(); validCompression.suspensionDampingRatioCompression = 1.2;
  assertDoesNotThrow(validCompression::validate);
  var validReboundAtUpperBound = new Overrides.Tuning(); validReboundAtUpperBound.suspensionDampingRatioRebound = 2.0;
  assertDoesNotThrow(validReboundAtUpperBound::validate);
 }

 @Test void unsetDirectionalFieldsHaveNoFallbackAndStayNullWhenNeverConfigured() {
  assertNull(Overrides.resolved("packid:not_configured").suspensionDampingRatioCompression);
  assertNull(Overrides.resolved("packid:not_configured").suspensionDampingRatioRebound);
  assertNull(Overrides.defaultTuning().suspensionDampingRatioCompression);
  assertNull(Overrides.defaultTuning().suspensionDampingRatioRebound);
 }

 @Test void directionalDampingRatioFieldsParticipateInTheFullPrecedenceChain() {
  try {
   var override = new Overrides.Tuning();
   override.suspensionDampingRatioCompression = 1.3; override.suspensionDampingRatioRebound = 0.4;
   Overrides.setForTest(java.util.Map.of("packid:directional_tuned_vehicle", override));
   var resolved = Overrides.resolved("packid:directional_tuned_vehicle");
   assertEquals(1.3, resolved.suspensionDampingRatioCompression);
   assertEquals(0.4, resolved.suspensionDampingRatioRebound);

   var packMetadata = new Overrides.Tuning(); packMetadata.suspensionDampingRatioCompression = 1.1;
   assertEquals(1.1, Overrides.resolved("packid:not_configured", packMetadata, null, null).suspensionDampingRatioCompression,
     "pack metadata must win over the (nonexistent) fallback when no operator override exists");
  } finally {
   Overrides.setForTest(java.util.Map.of());
  }
 }

 @Test void directionalDampingRatioFieldsAreRejectedOnAPartOverride() {
  var compression = new Overrides.Tuning(); compression.suspensionDampingRatioCompression = 1.0;
  assertThrows(IllegalArgumentException.class, compression::validateSupportedForPart);
  var rebound = new Overrides.Tuning(); rebound.suspensionDampingRatioRebound = 1.0;
  assertThrows(IllegalArgumentException.class, rebound::validateSupportedForPart);
 }

 @Test void directionalDampingRatioFieldsAreInheritedThroughExtends() {
  try {
   var template = new Overrides.Tuning();
   template.suspensionDampingRatioCompression = 1.5; template.suspensionDampingRatioRebound = 0.3;
   var child = new Overrides.Tuning(); child.extendsTemplate = "directional-tuned-template";
   Overrides.setForTest(java.util.Map.of("directional-tuned-template", template), java.util.Map.of("packid:inherits_directional_tuning", child), java.util.Map.of());
   var resolved = Overrides.resolved("packid:inherits_directional_tuning");
   assertEquals(1.5, resolved.suspensionDampingRatioCompression,
     "a child entry leaving suspensionDampingRatioCompression unset must inherit its template's own value");
   assertEquals(0.3, resolved.suspensionDampingRatioRebound);
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 @Test void invalidReloadRetainsThePriorGoodDirectionalDampingFields() {
  try {
   var good = new Overrides.Tuning(); good.suspensionDampingRatioCompression = 1.2;
   var goodResult = Overrides.apply(() -> new Overrides.Config(java.util.Map.of(), java.util.Map.of("packid:directional_reload", good), java.util.Map.of()));
   assertTrue(goodResult.success(), "the known-good config itself must apply cleanly");
   assertEquals(1.2, Overrides.resolved("packid:directional_reload").suspensionDampingRatioCompression);

   var invalid = new Overrides.Tuning(); invalid.suspensionDampingRatioCompression = -3.0;
   var badResult = Overrides.apply(() -> new Overrides.Config(java.util.Map.of(), java.util.Map.of("packid:directional_reload", invalid), java.util.Map.of()));
   assertFalse(badResult.success(), "an out-of-range reload must be rejected, not silently applied");

   assertEquals(1.2, Overrides.resolved("packid:directional_reload").suspensionDampingRatioCompression,
     "a rejected reload must retain the PRIOR GOOD value, not the invalid one or null");

   var removed = new Overrides.Tuning();
   var removedResult = Overrides.apply(() -> new Overrides.Config(java.util.Map.of(), java.util.Map.of("packid:directional_reload", removed), java.util.Map.of()));
   assertTrue(removedResult.success(), "removing the field entirely must itself be a valid reload");
   assertNull(Overrides.resolved("packid:directional_reload").suspensionDampingRatioCompression,
     "a reload that no longer sets the field must resolve back to null (inherit the per-wheel base), not retain the old value");
  } finally {
   Overrides.setForTest(java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
  }
 }

 @Test void directionalDampingRatioAloneCanBeRejectedForDestabilizingAnyAxlesEffectiveSpringMultiplier() {
  // suspensionDampingRatioCompression/Rebound are VEHICLE-WIDE once set - they must be checked
  // against EVERY axle's own effective spring multiplier (front/rear/base), not only the
  // vehicle-wide one, since once configured they apply to every wheel in that direction
  // regardless of which axle that wheel belongs to.
  var destabilizingAgainstFront = new Overrides.Tuning();
  destabilizingAgainstFront.suspensionTravel = 0.2;
  destabilizingAgainstFront.suspensionSpringMultiplierFront = 2.5; // only the front axle is stiff
  destabilizingAgainstFront.suspensionDampingRatioCompression = 1.8; // unstable specifically against the front axle's multiplier
  assertThrows(IllegalArgumentException.class, destabilizingAgainstFront::validate,
    "a vehicle-wide suspensionDampingRatioCompression must be rejected when it destabilizes even just the FRONT axle's own effective spring multiplier");

  var destabilizingAgainstRear = new Overrides.Tuning();
  destabilizingAgainstRear.suspensionTravel = 0.2;
  destabilizingAgainstRear.suspensionSpringMultiplierRear = 2.5;
  destabilizingAgainstRear.suspensionDampingRatioRebound = 1.8;
  assertThrows(IllegalArgumentException.class, destabilizingAgainstRear::validate,
    "a vehicle-wide suspensionDampingRatioRebound must be rejected when it destabilizes even just the REAR axle's own effective spring multiplier");

  // A safe directional ratio must remain accepted even with an axle-specific multiplier present.
  var safe = new Overrides.Tuning();
  safe.suspensionTravel = 0.2;
  safe.suspensionSpringMultiplierFront = 1.0; safe.suspensionSpringMultiplierRear = 1.0;
  safe.suspensionDampingRatioCompression = 0.9; safe.suspensionDampingRatioRebound = 1.1;
  assertDoesNotThrow(safe::validate);
 }

 @Test void equalCompressionAndReboundValuesReproduceTheCorrespondingExistingSymmetricSetting() {
  // Setting suspensionDampingRatioCompression==suspensionDampingRatioRebound (both to the same
  // value as a plain suspensionDampingRatio) must resolve to the exact same effective per-wheel
  // ratio as the existing symmetric field alone - the required default/equal-value equivalence.
  double sharedValue = 1.1;
  var symmetricOnly = new Overrides.Tuning(); symmetricOnly.suspensionDampingRatio = sharedValue;
  double axleResolvedBase = AxleSuspensionDistribution.dampingRatios(new double[]{1.5,-1.5}, symmetricOnly.suspensionDampingRatio, null, null)[0];
  assertEquals(sharedValue, axleResolvedBase, 0.0);

  var directional = new Overrides.Tuning();
  directional.suspensionDampingRatioCompression = sharedValue; directional.suspensionDampingRatioRebound = sharedValue;
  assertDoesNotThrow(directional::validate);
  // PhysicsBridge.directionalDampingRatio is the actual runtime resolution step - both
  // directions, given the SAME axle-resolved base used above, must resolve to the shared value.
  assertEquals(sharedValue, PhysicsBridge.directionalDampingRatio(directional.suspensionDampingRatioCompression, axleResolvedBase), 0.0);
  assertEquals(sharedValue, PhysicsBridge.directionalDampingRatio(directional.suspensionDampingRatioRebound, axleResolvedBase), 0.0);
 }

 /**
  * Review finding (comment 54547): {@code resolveFields} validated each of {@code packMetadata}/
  * {@code ivAdapted}/{@code physicallyDerived} individually (and {@code override} was already
  * validated at config-load time) but never validated the actual COMPOSED {@code result} it
  * returns - a field-by-field precedence chain can pick a directional damping ratio from one
  * source and an axle spring multiplier from a DIFFERENT source, each individually valid against
  * only its own source's other fields, yet unstable together once resolved onto the same wheel.
  * Reproduces the reviewer's own exact numbers: at travel 0.3, an operator-configured compression
  * ratio of 1.8 is safe alone (bound ~2.2167 at the unconfigured multiplier 1.0), and a pack-
  * metadata front spring multiplier of 4.0 is safe alone (bound ~0.948892900565 at the
  * unconfigured fallback ratio 0.7) - but {@code resolved()} composing the two together puts
  * ratio 1.8 against the front axle's OWN effective multiplier 4.0, whose bound is that same
  * ~0.9489, so the composed tuple is actually unstable and must now be rejected.
  */
 @Test void resolvedMustRejectACrossPrecedenceCombinationOfADirectionalRatioAndAnAxleSpringMultiplierThatIsUnstableOnlyTogether() {
  try {
   var operatorOverride = new Overrides.Tuning();
   operatorOverride.suspensionTravel = 0.3;
   operatorOverride.suspensionDampingRatioCompression = 1.8; // safe alone at the unconfigured multiplier 1.0
   assertDoesNotThrow(operatorOverride::validate, "test setup sanity: the operator's own override must be valid in isolation");

   var packMetadata = new Overrides.Tuning();
   packMetadata.suspensionSpringMultiplierFront = 4.0; // safe alone at the unconfigured fallback ratio 0.7
   assertDoesNotThrow(packMetadata::validate, "test setup sanity: the pack metadata's own fields must be valid in isolation");

   Overrides.setForTest(java.util.Map.of("packid:cross_precedence_unstable", operatorOverride));
   assertThrows(IllegalArgumentException.class, () -> Overrides.resolved("packid:cross_precedence_unstable", packMetadata, null, null),
     "a directional ratio from one precedence source combined with an axle spring multiplier from ANOTHER must be rejected when the EFFECTIVE combination is unstable, even though each source passes validation alone");

   // A safe cross-precedence combination must still resolve successfully - this is not a
   // blanket rejection of composing fields from different sources.
   var safePackMetadata = new Overrides.Tuning();
   safePackMetadata.suspensionSpringMultiplierFront = 1.0;
   var resolved = Overrides.resolved("packid:cross_precedence_unstable", safePackMetadata, null, null);
   assertEquals(1.8, resolved.suspensionDampingRatioCompression, 0.0);
   assertEquals(1.0, resolved.suspensionSpringMultiplierFront, 0.0);
  } finally {
   Overrides.setForTest(java.util.Map.of());
  }
 }
}
