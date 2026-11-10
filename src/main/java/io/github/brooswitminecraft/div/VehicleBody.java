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
}
