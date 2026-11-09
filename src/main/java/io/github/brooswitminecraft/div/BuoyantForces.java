package io.github.brooswitminecraft.div;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.Pose3d;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.entities.instances.PartEngine;
import minecrafttransportsimulator.entities.instances.PartGroundDevice;
import minecrafttransportsimulator.entities.instances.PartPropeller;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import java.util.List;

/**
 * Buoyancy/water-drag/propeller-thrust force application, shared by {@link BuoyantBody} (a
 * float-only hull's entire domain) and {@link PhysicsBridge}'s ground {@code Body} (an
 * amphibious vehicle - a drivable wheel/track AND at least one canFloat device - gets both the
 * ground solver's wheel forces and these, applied in the same tick, rather than this bridge
 * silently dropping its water behavior just because it also has wheels; see
 * Classification.floatEligible's javadoc). Archimedes buoyant force and quadratic water drag are
 * sampled per installed canFloat device at that device's own world position against the live
 * Minecraft water surface, which naturally produces a righting torque from uneven submersion -
 * no separate stability code needed. Installed propellers add forward/reverse thrust, steered by
 * rudder input, but only while the propeller itself is underwater.
 */
final class BuoyantForces {
 private BuoyantForces() {}

 /** Applies this tick's forces to handle and returns whether anything was submerged/thrusting
  * (the caller's cue to wake the Sable body). */
 static boolean apply(ServerLevel level, EntityVehicleF_Physics v, Overrides.Tuning tuning, Vector3d center,
     double hullVolume, Pose3d pose, Quaterniond q, Quaterniond inverse, Vector3d velocity, Vector3d angular,
     RigidBodyHandle handle) {
  var floats = floats(v);
  double volumePerFloat = floats.isEmpty() ? 0 : hullVolume / floats.size();
  boolean awake = false;
  for (var flt : floats) {
   double radius = Math.max(.1, flt.getHeight() / 2);
   Vector3d anchor = new Vector3d(flt.localOffset.x, flt.localOffset.y, flt.localOffset.z).sub(center);
   Vector3d arm = q.transform(new Vector3d(anchor));
   Vector3d point = new Vector3d(pose.position()).add(arm);
   double waterSurface = waterSurfaceAt(level, point.x, point.z, point.y + radius + 1);
   double fraction = Buoyancy.submergedFraction(point.y - radius, point.y + radius, waterSurface);
   flt.contactThisTick = fraction > 0;
   if (fraction <= 0) continue;
   flt.animateAsOnGround = true;
   awake = true;
   Vector3d localVelocity = new Vector3d(angular).cross(arm).add(velocity);
   double speed = localVelocity.length();
   double dragMagnitude = Buoyancy.drag(speed, fraction, tuning.waterDrag);
   Vector3d force = new Vector3d(0, Buoyancy.buoyantForce(fraction, volumePerFloat), 0);
   if (speed > 1e-6) force.fma(-dragMagnitude / speed, localVelocity);
   handle.applyImpulseAtPoint(inverse.transform(new Vector3d(arm)), inverse.transform(new Vector3d(force).mul(PhysicsBridge.DT)));
  }
  double drive = 0;
  for (var part : v.allParts) if (part instanceof PartEngine engine && engine.running)
   drive = Math.signum(engine.currentGearVar.currentValue) * v.throttleVar.currentValue;
  double steer = (-v.rudderInputVar.currentValue / EntityVehicleF_Physics.MAX_RUDDER_ANGLE) * Math.toRadians(tuning.maxSteerDegrees);
  Vector3d forward = q.transform(new Vector3d(Math.sin(steer), 0, Math.cos(steer)));
  int propellerCount = 0;
  for (var part : v.allParts) if (part instanceof PartPropeller) propellerCount++;
  for (var part : v.allParts) {
   if (!(part instanceof PartPropeller propeller)) continue;
   Vector3d anchor = new Vector3d(propeller.localOffset.x, propeller.localOffset.y, propeller.localOffset.z).sub(center);
   Vector3d arm = q.transform(new Vector3d(anchor));
   Vector3d point = new Vector3d(pose.position()).add(arm);
   boolean submerged = point.y <= waterSurfaceAt(level, point.x, point.z, point.y + 2);
   double thrust = Buoyancy.propellerThrust(drive, tuning.propellerForce / propellerCount, submerged);
   if (thrust == 0) continue;
   awake = true;
   Vector3d force = new Vector3d(forward).mul(thrust);
   handle.applyImpulseAtPoint(inverse.transform(arm), inverse.transform(new Vector3d(force).mul(PhysicsBridge.DT)));
  }
  return awake;
 }

 /** First water surface Y found scanning straight down from fromY, or NEGATIVE_INFINITY if none. */
 private static double waterSurfaceAt(ServerLevel level, double x, double z, double fromY) {
  var pos = new BlockPos.MutableBlockPos(Mth.floor(x), Mth.floor(fromY), Mth.floor(z));
  int bottom = Mth.floor(fromY) - 24;
  for (int y = Mth.floor(fromY); y >= bottom; y--) {
   pos.setY(y);
   var fluid = level.getFluidState(pos);
   if (fluid.is(FluidTags.WATER)) return y + fluid.getHeight(level, pos);
  }
  return Double.NEGATIVE_INFINITY;
 }

 private static List<PartGroundDevice> floats(EntityVehicleF_Physics v) {
  return v.allParts.stream().filter(p -> p instanceof PartGroundDevice g && g.definition != null
      && Classification.isFloatDevice(g.definition.ground))
    .map(p -> (PartGroundDevice) p).toList();
 }
}
