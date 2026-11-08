package io.github.brooswitminecraft.div;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
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
 void removeIf(BiPredicate<K, B> predicate, Consumer<B> onRemove) {
  active.entrySet().removeIf(e -> {
   if (!predicate.test(e.getKey(), e.getValue())) return false;
   onRemove.accept(e.getValue());
   return true;
  });
 }
}
