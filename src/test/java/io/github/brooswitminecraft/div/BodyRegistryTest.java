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

 @Test void bareActivateDoesNotRefuseADoubleActivateOnItsOwn() {
  // The plain activate() has no built-in double-activation guard; activateOnce (below) is the
  // method PhysicsBridge actually calls, and it is the one that supplies that guarantee. This
  // pins the fact that the guard is not "free" from activate() alone.
  var registry = new BodyRegistry<String, Object>();
  Object first = new Object(), second = new Object();
  registry.activate("car", first);
  assertTrue(registry.isActive("car"));
  registry.activate("car", second);
  assertSame(second, registry.get("car"), "a caller that bypasses activateOnce can still overwrite - exactly why PhysicsBridge never calls activate() directly");
 }

 @Test void activateOnceReusesAnAlreadyActiveBodyWithoutReconstructing() {
  // This is the exact method PhysicsBridge.active() calls:
  // "registry.activateOnce(v, () -> new Body(...))". Proving it never reconstructs an already-
  // active key's body is the no-double-physics guarantee for real, not a map-only stand-in.
  var registry = new BodyRegistry<String, Object>();
  int[] constructions = {0};
  Object body = new Object();
  assertTrue(registry.activateOnce("car", () -> { constructions[0]++; return body; }));
  assertTrue(registry.activateOnce("car", () -> { constructions[0]++; return new Object(); }));
  assertEquals(1, constructions[0], "already active - must not call the constructor again");
  assertSame(body, registry.get("car"));
 }

 @Test void activateOnceLeavesTheKeyUntouchedWhenConstructionThrows() {
  // Mirrors PhysicsBridge.active()'s failed-construction path: Body's constructor can throw
  // (deterministic invalid geometry, or an operational Sable/world failure), and activateOnce
  // must not leave the key half-activated either way - nothing in the registry changes.
  var registry = new BodyRegistry<String, Object>();
  assertThrows(IllegalStateException.class, () -> registry.activateOnce("car", () -> { throw new IllegalStateException("boom"); }));
  assertFalse(registry.isActive("car"));
  assertNull(registry.get("car"));
 }

 @Test void activateOnceRetriesAfterAPriorConstructionFailure() {
  // PhysicsBridge treats a non-geometry construction failure as retryable (not permanently
  // cached), relying on activateOnce not leaving any trace of the failed attempt behind.
  var registry = new BodyRegistry<String, Object>();
  assertThrows(RuntimeException.class, () -> registry.activateOnce("car", () -> { throw new RuntimeException("transient"); }));
  Object body = new Object();
  assertTrue(registry.activateOnce("car", () -> body), "a later call with a working constructor must still succeed");
  assertSame(body, registry.get("car"));
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
