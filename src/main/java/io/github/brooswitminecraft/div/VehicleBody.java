package io.github.brooswitminecraft.div;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
/** One server-owned Sable body for a single vehicle, regardless of which physics domain (ground
 * suspension, buoyancy, ...) produced it. PhysicsBridge's registry and lifecycle code deal only
 * in this interface, so adding a domain never has to touch that shared bookkeeping. */
interface VehicleBody {
 void step(EntityVehicleF_Physics v);
 void remove();
 /**
  * MINECRAFT-545/547 (John's comment 55098): {@code true} exactly when this body's own native
  * physics object is still registered with the physics system - {@code false} once {@link
  * #remove()} has actually run for it, whether that happened through this registry's own
  * {@code cleanup()}/{@code unload()} sweep or through some OTHER removal path that reached the
  * same native object without going through this registry at all (the actual gap this guards
  * against - see {@code PhysicsBridge.step}'s own doc for the exact crash this closes). Reads
  * the SAME {@code BoxPhysicsObject.isActive()} signal {@code debugCapturedBoxIsActive} already
  * exposes as a live observation elsewhere in this file - not a new liveness mechanism, the
  * existing one, now also consulted before stepping rather than only from diagnostics.
  */
 boolean isActive();
 PhysicsBridge.InheritedRigidBodyVelocity captureReplacementState(EntityVehicleF_Physics v);
 /** Identity of the level this body lives in, for PhysicsBridge.unload()'s level-unload sweep. */
 Object level();
 /**
  * The live {@code Overrides.Tuning} this body was constructed with (ticket 53518/MINECRAFT-505)
  * - the SAME object reference every tick/call already reads fields off of, never a copy. Hot
  * reload mutates this object's own fields in place for whichever ones a given implementation
  * re-reads fresh each tick/call (see each implementation's own construction code for which
  * those are) - PhysicsBridge.reloadOverrides() is the only caller.
  */
 Overrides.Tuning tuning();
}
