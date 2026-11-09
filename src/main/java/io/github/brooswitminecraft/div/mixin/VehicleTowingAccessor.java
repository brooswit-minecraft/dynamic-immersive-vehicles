package io.github.brooswitminecraft.div.mixin;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
/** Exposes IV's own protected getRecursiveTowingThrust() - the summed engine thrust of
 * everything a vehicle is towing - so PhysicsBridge can replicate it for a towing vehicle whose
 * native getForcesAndMotions never runs (see VehicleForcesMixin). */
@Mixin(value=EntityVehicleF_Physics.class, remap=false)
public interface VehicleTowingAccessor {
 @Invoker("getRecursiveTowingThrust") double div$getRecursiveTowingThrust();
}
