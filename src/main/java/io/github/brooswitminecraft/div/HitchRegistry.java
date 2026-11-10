package io.github.brooswitminecraft.div;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Pure joint-lifecycle bookkeeping, by identity, for "which towing-vehicle key (if any) is this
 * trailer key currently jointed to" - the same kind of seam {@link BodyRegistry} provides for
 * body lifecycle, deliberately kept separate from it and from any live entity/Sable type (only
 * {@link PhysicsConstraintHandle}, a small interface with no live-runtime dependency of its own,
 * is required), so PhysicsBridge's hitch-joint reconciliation and release are testable with fake
 * keys and a fake handle instead of a live entity/world/Sable runtime. Operating on keys only -
 * never touching a body - is what proves a towing vehicle losing its own body can never also
 * remove the trailer's: this class has no way to reach a body at all.
 */
final class HitchRegistry<K> {
 private final Map<K, PhysicsConstraintHandle> joints = new IdentityHashMap<>();
 private final Map<K, K> partners = new IdentityHashMap<>();
 boolean isJointed(K trailer) { return joints.containsKey(trailer); }
 K partnerOf(K trailer) { return partners.get(trailer); }
 /**
  * Whether {@code trailer} currently holds a joint to exactly {@code desired}, with a still-
  * valid handle - the stronger fact {@code PhysicsBridge.reconcileOrRollBack} needs to tell a
  * previously healthy hitch (safe to preserve if a later reconcile attempt fails) from a stale,
  * invalid, or mismatched-partner one. Plain map membership ({@link #isJointed}) is not enough
  * for that: {@link #reconcile} tears down an invalid or mismatched existing joint regardless of
  * whether its replacement succeeds, so by the time a failed replacement is discovered, the old
  * entry this trailer "was jointed" to is already gone - checking only {@code isJointed} before
  * that call wrongly treats the about-to-be-destroyed stale entry as equivalent to a currently
  * healthy one. This mirrors {@link #reconcile}'s own no-op condition exactly, so
  * {@code isCoherentlyJointedTo(trailer, desired)} being true implies a {@link #reconcile} call
  * with that same {@code desired} would be a no-op (never call {@code release} or {@code create}).
  */
 boolean isCoherentlyJointedTo(K trailer, K desired) {
  PhysicsConstraintHandle existing = joints.get(trailer);
  return existing != null && existing.isValid() && Objects.equals(partners.get(trailer), desired);
 }
 /**
  * Brings {@code trailer}'s joint in line with {@code desired} (its towing-vehicle key, or
  * {@code null} if it should have no joint right now): does nothing if already correctly
  * jointed to {@code desired} with a still-valid handle, otherwise releases any existing joint
  * and, only if {@code desired} is non-null, constructs exactly one new one via {@code create}.
  * {@code create} is never called when the existing joint already matches and is valid, and
  * never called at all when {@code desired} is {@code null}.
  */
 void reconcile(K trailer, K desired, Supplier<PhysicsConstraintHandle> create) {
  PhysicsConstraintHandle existing = joints.get(trailer);
  if (Objects.equals(desired, partners.get(trailer)) && (existing == null || existing.isValid())) return;
  release(trailer);
  if (desired == null) return;
  joints.put(trailer, create.get());
  partners.put(trailer, desired);
 }
 /**
  * Releases {@code key}'s own joint if it is a trailer, and any joints held by trailers
  * currently jointed TO {@code key} if it is (also) a towing vehicle - without touching any
  * body. Safe (a no-op) if {@code key} holds no joint in either role.
  */
 void release(K key) {
  PhysicsConstraintHandle own = joints.remove(key);
  if (own != null) own.remove();
  partners.remove(key);
  partners.entrySet().removeIf(e -> {
   if (!Objects.equals(e.getValue(), key)) return false;
   PhysicsConstraintHandle joint = joints.remove(e.getKey());
   if (joint != null) joint.remove();
   return true;
  });
 }
}
