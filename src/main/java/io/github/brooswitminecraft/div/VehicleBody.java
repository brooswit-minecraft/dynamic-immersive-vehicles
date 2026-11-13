package io.github.brooswitminecraft.div;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
/** One server-owned Sable body for a single vehicle, regardless of which physics domain (ground
 * suspension, buoyancy, ...) produced it. PhysicsBridge's registry and lifecycle code deal only
 * in this interface, so adding a domain never has to touch that shared bookkeeping. */
interface VehicleBody {
 void step(EntityVehicleF_Physics v);
 void remove();
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
