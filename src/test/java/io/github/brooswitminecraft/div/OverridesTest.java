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
  assertNull(r.massKg, "massKg has no flat fallback here - PhysicsBridge falls back to the vehicle's own native mass instead");
 }

 @Test void resolvedNeverReturnsANullFieldForThoseWithAFallback() {
  // Every field below the always-populated FALLBACK level must come out non-null regardless of
  // which upstream sources are present - a caller must never see a sparse Tuning for these.
  var r = Overrides.resolved("packid:not_configured", null, null, null);
  assertNotNull(r.suspensionTravel); assertNotNull(r.friction); assertNotNull(r.driveForce);
  assertNotNull(r.brakeForce); assertNotNull(r.maxSteerDegrees);
  assertNotNull(r.hullFillFactor); assertNotNull(r.waterDrag); assertNotNull(r.propellerForce);
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
}
