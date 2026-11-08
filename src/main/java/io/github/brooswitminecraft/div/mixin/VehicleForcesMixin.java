package io.github.brooswitminecraft.div.mixin;
import io.github.brooswitminecraft.div.PhysicsBridge;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=EntityVehicleF_Physics.class, remap=false)
public abstract class VehicleForcesMixin {
 @Inject(method="getForcesAndMotions", at=@At("HEAD"), cancellable=true)
 private void div$forces(CallbackInfo ci) {
  if (PhysicsBridge.active((EntityVehicleF_Physics)(Object)this)) ci.cancel();
 }
}
