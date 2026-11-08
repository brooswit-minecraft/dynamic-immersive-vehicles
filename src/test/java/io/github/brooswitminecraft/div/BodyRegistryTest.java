package io.github.brooswitminecraft.div;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * PhysicsBridge relies on exactly this bookkeeping to guarantee no vehicle is ever given two
 * live bodies at once (no double physics) and that an old body is always handed back for
 * removal before a new one replaces it (no leaked ownership). This is the actual production
 * class, not a stand-in, exercised with plain keys/bodies instead of a live entity/Sable
 * runtime - the one piece of that lifecycle guarantee that can run as a unit test.
 */
class BodyRegistryTest {
 @Test void freshKeyIsNotActive() {
  var registry = new BodyRegistry<String, Object>();
  assertFalse(registry.isActive("car"));
  assertNull(registry.get("car"));
 }

 @Test void activateMakesItActiveAndRetrievable() {
  var registry = new BodyRegistry<String, Object>();
  Object body = new Object();
  registry.activate("car", body);
  assertTrue(registry.isActive("car"));
  assertSame(body, registry.get("car"));
 }

 @Test void secondActivateCallIsWhatPhysicsBridgeMustNeverDoWithoutCheckingFirst() {
  // PhysicsBridge always guards activate() with "if (registry.isActive(v)) return true;" so a
  // key is never activated twice while already active. This pins the registry's half of that
  // contract: it does not itself refuse a double activate, so the caller-side guard is load-
  // bearing, and isActive is exactly the signal that guard reads.
  var registry = new BodyRegistry<String, Object>();
  Object first = new Object(), second = new Object();
  registry.activate("car", first);
  assertTrue(registry.isActive("car"));
  registry.activate("car", second);
  assertSame(second, registry.get("car"), "a caller that ignores isActive() can still overwrite - exactly why PhysicsBridge checks it first");
 }

 @Test void deactivateReturnsTheOldBodyForTheCallerToDestroy() {
  var registry = new BodyRegistry<String, Object>();
  Object body = new Object();
  registry.activate("car", body);
  assertSame(body, registry.deactivate("car"));
  assertFalse(registry.isActive("car"));
 }

 @Test void deactivateOnAnAlreadyInactiveKeyReturnsNullNotAThrow() {
  var registry = new BodyRegistry<String, Object>();
  assertNull(registry.deactivate("never-activated"));
 }

 @Test void removeIfDestroysOnlyMatchingKeysAndDeactivatesThem() {
  var registry = new BodyRegistry<String, Object>();
  registry.activate("keep", new Object());
  registry.activate("drop", new Object());
  List<Object> destroyed = new ArrayList<>();
  registry.removeIf((key, body) -> key.equals("drop"), destroyed::add);
  assertTrue(registry.isActive("keep"));
  assertFalse(registry.isActive("drop"));
  assertEquals(1, destroyed.size());
 }

 @Test void keysAreComparedByIdentityNotEquality() {
  // PhysicsBridge keys the active-body map by the live entity instance and keys
  // brokenDefinitions by the shared JSON definition object; both rely on identity, not a
  // custom equals/hashCode, since IV entities/definitions do not define either.
  var registry = new BodyRegistry<StringBuilder, Object>();
  var a = new StringBuilder("car");
  var b = new StringBuilder("car");
  registry.activate(a, new Object());
  assertTrue(registry.isActive(a));
  assertFalse(registry.isActive(b), "a distinct instance with equal content must not be treated as the same key");
 }
}
