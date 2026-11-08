package io.github.brooswitminecraft.div;
/**
 * Thrown only by Body's own explicit, pre-registration chassis-bounds check: the one
 * construction failure PhysicsBridge knows is deterministic for a given vehicle definition
 * (the same collision geometry will fail the same way every time), and so the only one safe to
 * cache in brokenDefinitions. Any other exception from Body's constructor - including an
 * unrelated IllegalArgumentException thrown by BoxPhysicsObject, RigidBodyHandle.of, or Sable's
 * own velocity setup - is NOT this type, and PhysicsBridge.active() must leave it retryable.
 */
final class InvalidChassisGeometry extends RuntimeException {
 InvalidChassisGeometry(String message) { super(message); }
}
