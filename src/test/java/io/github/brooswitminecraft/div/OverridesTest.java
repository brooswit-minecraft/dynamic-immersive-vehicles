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
  Overrides.DEFAULT.validate();
  assertNull(Overrides.DEFAULT.enabled);
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

 @Test void resolvedNeverReturnsANullField() {
  // Every field below the always-populated FALLBACK level must come out non-null regardless of
  // which upstream sources are present - a caller must never see a sparse Tuning.
  var r = Overrides.resolved("packid:not_configured", null, null);
  assertNotNull(r.suspensionTravel); assertNotNull(r.friction); assertNotNull(r.driveForce);
  assertNotNull(r.brakeForce); assertNotNull(r.maxSteerDegrees);
  assertNotNull(r.hullFillFactor); assertNotNull(r.waterDrag); assertNotNull(r.propellerForce);
 }

 @Test void packMetadataFillsAGapBelowAMissingOperatorOverrideButAboveTheFallback() {
  var packMetadata = new Overrides.Tuning();
  packMetadata.suspensionTravel = 0.45;
  var r = Overrides.resolved("packid:not_configured", packMetadata, null);
  assertEquals(0.45, r.suspensionTravel, "with no operator override, pack DIV metadata must win over the documented fallback");
  assertEquals(1.0, r.friction, "a field pack metadata doesn't mention must still fall through to the documented fallback");
 }

 @Test void ivAdaptedValueFillsAGapBelowBothOperatorOverrideAndPackMetadata() {
  var ivAdapted = new Overrides.Tuning();
  ivAdapted.friction = 0.85;
  var r = Overrides.resolved("packid:not_configured", null, ivAdapted);
  assertEquals(0.85, r.friction, "with no operator override or pack metadata, an IV-adapted native value must win over the documented fallback");
 }

 @Test void precedenceOrderIsOperatorOverrideThenPackMetadataThenIvAdaptedThenFallback() {
  var packMetadata = new Overrides.Tuning(); packMetadata.friction = 0.5;
  var ivAdapted = new Overrides.Tuning(); ivAdapted.friction = 0.6;
  // No operator override entry exists for this key, so pack metadata must win over the (also
  // present) IV-adapted value - precedence, not "whichever is supplied".
  var r = Overrides.resolved("packid:not_configured", packMetadata, ivAdapted);
  assertEquals(0.5, r.friction, "pack DIV metadata must take precedence over an IV-adapted value when both are present");
 }
}
