package io.github.brooswitminecraft.div;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
/**
 * Pure bookkeeping, by identity, for "does this key currently own a constructed body". A
 * caller that only ever calls {@code activate} after seeing {@code isActive} return false is
 * guaranteed never to hold two live bodies for the same key at once (no double physics), and
 * {@code deactivate} always hands back the previous body (if any) for the caller to actually
 * destroy, rather than silently dropping it (no leaked ownership). Not itself aware of
 * entities, Sable, or a world, so it can run as an ordinary unit test.
 */
final class BodyRegistry<K, B> {
 private final Map<K, B> active = new IdentityHashMap<>();
 boolean isActive(K key) { return active.containsKey(key); }
 B get(K key) { return active.get(key); }
 void activate(K key, B body) { active.put(key, body); }
 /** Removes and returns the key's current body, or null if it had none, for the caller to destroy. */
 B deactivate(K key) { return active.remove(key); }
 /**
  * Reuses the key's existing body if it is already active, without calling {@code construct}
  * again (the guard PhysicsBridge.active() relies on for "no double physics"); otherwise
  * constructs and activates exactly one new body. If {@code construct} throws, nothing is
  * activated - the key is left exactly as it was before the call, for the caller to decide
  * what the failure means (PhysicsBridge distinguishes a deterministic geometry failure,
  * cached, from a transient one, left retryable) rather than this class deciding for them.
  */
 boolean activateOnce(K key, Supplier<B> construct) {
  if (isActive(key)) return true;
  activate(key, construct.get());
  return true;
 }
 /**
  * {@code onRemove} receives the key as well as the body - unlike the single-key {@code
  * deactivate}, a bulk sweep (cleanup/unload) is also where PhysicsBridge must release any hitch
  * joint keyed by that same entity (either as the towed side's own joint, or as a towing
  * vehicle whose trailers' joints must be released), which needs the key, not just the body.
  *
  * <p>MINECRAFT-545/547 (Wroosbit review, comment 55189): a throwing {@code onRemove} used to
  * propagate straight out of the underlying {@code Map.Entry} collection's own {@code
  * removeIf} - since that collection view's {@code removeIf} aborts its ENTIRE iteration on the
  * first exception from its own predicate lambda, every entry after the throwing one (in
  * iteration order) was never even visited, and the throwing entry itself was never removed
  * (its own predicate lambda never returned normally). {@code onRemove} is now individually
  * try/caught per entry, so one entry's own failure can never prevent any other matching
  * entry's own {@code onRemove} from still running. The failing entry IS still removed despite
  * its own {@code onRemove} throwing: the predicate above already confirmed this entry meets
  * the caller's own removal criterion (e.g. {@code !v.isValid}), so retaining it would only
  * repeat the identical failure on every future sweep, for no benefit - removing it reflects
  * "every attempt was made, the entry's own fate is decided," while the exception is still
  * re-thrown (the FIRST one encountered across the whole sweep, never swallowed) only after
  * every matching entry has been attempted, so the failure is reported but does not block
  * progress on unrelated entries. Each entry is visited and (if matching) removed/attempted at
  * most once per call, so a second call naturally cannot double-touch an entry this call
  * already removed - there is nothing left in {@code active} for it to match again.
  */
 void removeIf(BiPredicate<K, B> predicate, BiConsumer<K, B> onRemove) {
  RuntimeException[] firstFailure = new RuntimeException[1];
  active.entrySet().removeIf(e -> {
   if (!predicate.test(e.getKey(), e.getValue())) return false;
   try { onRemove.accept(e.getKey(), e.getValue()); }
   catch (RuntimeException ex) { if (firstFailure[0] == null) firstFailure[0] = ex; }
   return true;
  });
  if (firstFailure[0] != null) throw firstFailure[0];
 }
 /**
  * Read-only visit of every currently-active (key, body) pair (ticket 53518/MINECRAFT-505's
  * hot-reload propagation: live-updating every already-active body's tuning needs every one of
  * them, not just one key). {@code consumer} must not itself activate/deactivate/remove an
  * entry - this iterates {@code active} directly (no defensive copy), the same assumption
  * {@code removeIf} above already makes about its own callbacks.
  */
 void forEachActive(BiConsumer<K, B> consumer) { active.forEach(consumer); }
}
