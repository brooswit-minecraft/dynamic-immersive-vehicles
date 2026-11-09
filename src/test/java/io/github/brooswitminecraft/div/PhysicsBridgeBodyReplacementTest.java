package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@code PhysicsBridge.replaceIfCategoryChanged(...)}, the exact (package-private)
 * method {@code active()} calls to fix the body-category lifecycle gap review found: a float-only
 * hull (initially a {@code BuoyantBody}) that has an {@code isRotor} propeller installed, or a
 * rotor-only hull (initially a ground/flight/rotor {@code Body}) that has its rotor removed, must
 * have its stale body replaced rather than silently kept - {@code registry.activateOnce} alone
 * deliberately reuses an already-active body without reconstructing it, so this replacement must
 * happen first. Uses plain keys and fake bodies instead of a live entity/Sable runtime - not a
 * parallel stand-in, but the literal method {@code active()} calls.
 */
class PhysicsBridgeBodyReplacementTest {
 private static final class FakeBody {
  boolean removed = false;
 }

 @Test void noOpWhenNoExistingBody() {
  var registry = new BodyRegistry<String, FakeBody>();
  PhysicsBridge.replaceIfCategoryChanged(registry, "nope", true, b -> true, b -> fail("remove must not be called"));
  assertNull(registry.get("nope"));
 }

 @Test void noOpWhenExistingBodysCategoryAlreadyMatches() {
  var registry = new BodyRegistry<String, FakeBody>();
  var body = new FakeBody();
  registry.activate("car", body);
  PhysicsBridge.replaceIfCategoryChanged(registry, "car", true, b -> true, b -> b.removed = true);
  assertSame(body, registry.get("car"), "matching category: the existing body is left in place");
  assertFalse(body.removed);
 }

 @Test void replacesStaleFloatBodyWhenAnInstalledRotorMakesItGroundEligible() {
  // The exact lifecycle gap review found: a float-only hull's BuoyantBody must be removed once
  // an isRotor propeller is installed and Classification.eligible now claims the vehicle (ground
  // category, via isRotorcraft) - otherwise registry.activateOnce would keep reusing the stale
  // BuoyantBody, whose BuoyantForces.apply explicitly excludes rotor propellers from thrust, so
  // the newly installed rotor would never get stepRotor's lift/control.
  var registry = new BodyRegistry<String, FakeBody>();
  var floatBody = new FakeBody();
  registry.activate("hull", floatBody);
  PhysicsBridge.replaceIfCategoryChanged(registry, "hull", true, b -> b != floatBody, b -> b.removed = true);
  assertNull(registry.get("hull"), "stale float body removed - active() goes on to construct a fresh ground/rotor Body");
  assertTrue(floatBody.removed, "the old body is actually destroyed, not leaked");
 }

 @Test void replacesStaleGroundBodyWhenItsOnlyRotorIsRemovedMakingItFloatEligible() {
  // The reverse transition: a rotor-only hull (no wheels/tracks, eligible only via its installed
  // isRotor propeller) has that rotor removed and falls back to float-only eligibility - the
  // stale ground/rotor Body must be removed too, not left running stepRotor against a propeller
  // that no longer exists.
  var registry = new BodyRegistry<String, FakeBody>();
  var groundBody = new FakeBody();
  registry.activate("hull", groundBody);
  PhysicsBridge.replaceIfCategoryChanged(registry, "hull", false, b -> b == groundBody, b -> b.removed = true);
  assertNull(registry.get("hull"), "stale ground body removed - active() goes on to construct a fresh BuoyantBody");
  assertTrue(groundBody.removed);
 }
}
