package io.github.brooswitminecraft.div.mixin;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
/** Exposes IV's own protected getRecursiveTowingThrust() - the summed engine thrust of
 * everything a vehicle is towing - so PhysicsBridge can replicate it for a towing vehicle whose
 * native getForcesAndMotions never runs (see VehicleForcesMixin); and the private
 * thrustForceValue field each level of that recursion reads, so PhysicsBridge can sum only the
 * un-jointed portion itself (see PhysicsBridge.unjointedTowingThrust) without double-counting a
 * now-independently-Sable-simulated trailer's engine thrust. */
@Mixin(value=EntityVehicleF_Physics.class, remap=false)
public interface VehicleTowingAccessor {
 @Invoker("getRecursiveTowingThrust") double div$getRecursiveTowingThrust();
 @Accessor("thrustForceValue") double div$getThrustForceValue();
}
