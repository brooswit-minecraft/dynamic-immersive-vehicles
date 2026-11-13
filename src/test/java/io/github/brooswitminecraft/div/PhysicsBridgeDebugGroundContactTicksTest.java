package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Parse/validate coverage for {@code -Ddiv.debugGroundContactTicks} (ticket comments 53095/53098:
 * the previous hardcoded 200-tick ground-contact trace window, counted from placement rather
 * than control input, was too short to reach throttle onset in a session that idled before
 * driving - this makes the window configurable). A pure static method on {@link PhysicsBridge},
 * same extraction rationale as {@code suspensionSpring} - testable with no Minecraft/SLF4J
 * dependency.
 */
class PhysicsBridgeDebugGroundContactTicksTest {
 @Test void nullPropertyFallsBackToDefault() {
  assertEquals(PhysicsBridge.DEFAULT_DEBUG_GROUND_CONTACT_TICKS, PhysicsBridge.resolveDebugGroundContactTicks(null));
 }
 @Test void blankOrNonNumericFallsBackToDefault() {
  assertEquals(PhysicsBridge.DEFAULT_DEBUG_GROUND_CONTACT_TICKS, PhysicsBridge.resolveDebugGroundContactTicks(""));
  assertEquals(PhysicsBridge.DEFAULT_DEBUG_GROUND_CONTACT_TICKS, PhysicsBridge.resolveDebugGroundContactTicks("not-a-number"));
  assertEquals(PhysicsBridge.DEFAULT_DEBUG_GROUND_CONTACT_TICKS, PhysicsBridge.resolveDebugGroundContactTicks("1200ticks"));
 }
 @Test void zeroOrNegativeFallsBackToDefault() {
  assertEquals(PhysicsBridge.DEFAULT_DEBUG_GROUND_CONTACT_TICKS, PhysicsBridge.resolveDebugGroundContactTicks("0"));
  assertEquals(PhysicsBridge.DEFAULT_DEBUG_GROUND_CONTACT_TICKS, PhysicsBridge.resolveDebugGroundContactTicks("-5"));
 }
 @Test void aboveTheDocumentedUpperBoundFallsBackToDefault() {
  assertEquals(PhysicsBridge.DEFAULT_DEBUG_GROUND_CONTACT_TICKS,
    PhysicsBridge.resolveDebugGroundContactTicks(Integer.toString(PhysicsBridge.MAX_DEBUG_GROUND_CONTACT_TICKS+1)));
 }
 @Test void exactlyTheUpperBoundIsAccepted() {
  assertEquals(PhysicsBridge.MAX_DEBUG_GROUND_CONTACT_TICKS,
    PhysicsBridge.resolveDebugGroundContactTicks(Integer.toString(PhysicsBridge.MAX_DEBUG_GROUND_CONTACT_TICKS)));
 }
 @Test void validInRangeValueIsUsedAsIs() {
  assertEquals(1200, PhysicsBridge.resolveDebugGroundContactTicks("1200"));
 }
 @Test void surroundingWhitespaceIsTolerated() {
  assertEquals(1200, PhysicsBridge.resolveDebugGroundContactTicks(" 1200 "));
 }
}
