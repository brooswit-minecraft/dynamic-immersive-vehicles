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
  t.friction = 0;
  assertThrows(IllegalArgumentException.class, t::validate);
 }

 @Test void missingMassKgIsAllowed() {
  var t = new Overrides.Tuning();
  t.massKg = null;
  assertDoesNotThrow(t::validate);
 }
}
