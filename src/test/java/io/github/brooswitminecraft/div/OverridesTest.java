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
  assertEquals(FlightForces.DEFAULT_STALL_ANGLE_DEGREES, r.stallAngleDegrees, "stallAngleDegrees falls back to FlightForces's own previously-hardcoded 15deg");
  assertEquals(FlightForces.DEFAULT_LIFT_SLOPE, r.liftSlope, 1e-9, "liftSlope falls back to FlightForces's own previously-hardcoded 2*pi");
  assertEquals(FlightForces.DEFAULT_CONTROL_SURFACE_SLOPE, r.controlSurfaceSlope, 1e-9, "controlSurfaceSlope falls back to the same previously-hardcoded 2*pi");
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
  assertNotNull(r.stallAngleDegrees); assertNotNull(r.liftSlope); assertNotNull(r.controlSurfaceSlope);
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
 // - a parts entry may only set driveForce today; every other field is rejected at load/reload,
 // including one reached only through extends, not silently accepted-but-ignored.

 @Test void partEntrySettingOnlyDriveForcePassesTheSupportedFieldsCheck() {
  var part = new Overrides.Tuning(); part.driveForce = 9500.0;
  assertDoesNotThrow(part::validateSupportedForPart);
 }

 @Test void partEntrySettingAnUnsupportedFieldFailsTheSupportedFieldsCheck() {
  var frictionOverride = new Overrides.Tuning(); frictionOverride.friction = 0.9;
  assertThrows(IllegalArgumentException.class, frictionOverride::validateSupportedForPart);
  var brakeOverride = new Overrides.Tuning(); brakeOverride.brakeForce = 1000.0;
  assertThrows(IllegalArgumentException.class, brakeOverride::validateSupportedForPart);
  var selfRightOverride = new Overrides.Tuning(); selfRightOverride.selfRightStrength = 1.0;
  assertThrows(IllegalArgumentException.class, selfRightOverride::validateSupportedForPart);
 }

 @Test void partEntryInheritingAnUnsupportedFieldThroughExtendsStillFailsTheSupportedFieldsCheck() {
  try {
   var template = new Overrides.Tuning(); template.friction = 0.9; // not supported for a part
   var part = new Overrides.Tuning(); part.extendsTemplate = "base"; part.driveForce = 9500.0;
   Overrides.setForTest(java.util.Map.of("base", template), java.util.Map.of(), java.util.Map.of());
   var merged = Overrides.effective(part);
   assertThrows(IllegalArgumentException.class, merged::validateSupportedForPart,
     "a part must not be able to smuggle an unsupported field in through extends");
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
}
