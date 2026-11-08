package io.github.brooswitminecraft.div.mixin;
import io.github.brooswitminecraft.div.PhysicsBridge;
import minecrafttransportsimulator.baseclasses.Point3D;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets="minecrafttransportsimulator.entities.instances.AEntityVehicleD_Moving", remap=false)
public abstract class VehicleMovementMixin {
 @Shadow @Final private Point3D motionApplied;
 @Shadow @Final private Point3D serverDeltaM;
 @Shadow @Final private Point3D serverDeltaR;
 @Shadow @Final private Point3D clientDeltaM;
 @Shadow @Final private Point3D clientDeltaR;
 @Inject(method="performGroundOperations", at=@At("HEAD"), cancellable=true)
 private void div$ground(CallbackInfo ci) {
  if (PhysicsBridge.active((EntityVehicleF_Physics)(Object)this)) ci.cancel();
 }
 @Inject(method="moveVehicle", at=@At("HEAD"), cancellable=true)
 private void div$move(CallbackInfo ci) {
  EntityVehicleF_Physics vehicle = (EntityVehicleF_Physics)(Object)this;
  if (!PhysicsBridge.active(vehicle)) return;
  if (vehicle.world.isClient()) {
   // Consume the existing IV movement stream instead of running a second physics solver.
   motionApplied.set(serverDeltaM).subtract(clientDeltaM);
   vehicle.position.add(motionApplied);
   vehicle.motion.set(motionApplied).scale(1 / vehicle.speedFactor);
   vehicle.orientation.angles.add(serverDeltaR.copy().subtract(clientDeltaR));
   vehicle.orientation.updateToAngles();
   clientDeltaM.set(serverDeltaM);
   clientDeltaR.set(serverDeltaR);
  } else {
   Point3D before = vehicle.position.copy();
   PhysicsBridge.step(vehicle);
   motionApplied.set(vehicle.position).subtract(before);
   vehicle.addToServerDeltas(null, null, 0);
  }
  ci.cancel();
 }
}
