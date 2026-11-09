package io.github.brooswitminecraft.div;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.Pose3d;
import io.github.brooswitminecraft.div.mixin.PartEngineAccessor;
import io.github.brooswitminecraft.div.mixin.PartPropellerAccessor;
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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Buoyancy/water-drag/propeller-thrust force application, shared by {@link BuoyantBody} (a
 * float-only hull's entire domain) and {@link PhysicsBridge}'s ground/flight {@code Body} (an
 * amphibious vehicle - a drivable wheel/track, or a rotorcraft/fixed-wing/blimp, with at least
 * one canFloat device - gets both its ground/flight domain's own forces and these, applied in
 * the same tick, rather than this bridge silently dropping its water behavior just because it
 * also drives or flies; see Classification.floatEligible's javadoc). Archimedes buoyant force
 * and quadratic water drag are sampled per installed canFloat device at that device's own world
 * position against the live Minecraft water surface, which naturally produces a righting torque
 * from uneven submersion - no separate stability code needed. Installed non-rotor propellers add
 * forward/reverse thrust, steered by rudder input, allocated per actual engine-to-propeller
 * linkage (not reachable through IV's public API - see {@link PartEngineAccessor}/
 * {@link PartPropellerAccessor}) rather than one vehicle-wide signal applied to every installed
 * propeller; an installed rotor propeller is excluded here deliberately - its thrust is already
 * supplied by {@code PhysicsBridge.Body.stepRotor}'s own collective/cyclic model regardless of
 * water, so counting it again here would double-apply the same propulsive force.
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
  // Per-propeller drive, keyed by actual engine linkage (PartEngineAccessor.linkedPropellers -
  // IV's own private field, not reachable through any public API) rather than one vehicle-wide
  // signal applied to every installed propeller regardless of whether a given engine drives it
  // at all. If two running engines both link the same propeller, the first one found sets it -
  // that propeller is not double-driven by being added to twice. Direction comes from the
  // propeller's own desiredLinearVelocity (see Buoyancy.signedPropellerDrive), not the engine's
  // transmission gear - a propeller has no gearbox of its own, and a gear-based sign disagreed
  // with IV's real forward/reverse state whenever an engine sat in neutral while still turning
  // a propeller, or a dynamic-pitch propeller was reversed via reverseThrustVar.
  Map<PartPropeller, Double> drivenBy = new IdentityHashMap<>();
  for (var part : v.allParts) {
   if (!(part instanceof PartEngine engine) || !engine.running) continue;
   for (var linked : ((PartEngineAccessor)(Object)engine).div$linkedPropellers()) {
    drivenBy.putIfAbsent(linked, Buoyancy.signedPropellerDrive(linked.desiredLinearVelocity, v.throttleVar.currentValue));
   }
  }
  double steer = (-v.rudderInputVar.currentValue / EntityVehicleF_Physics.MAX_RUDDER_ANGLE) * Math.toRadians(tuning.maxSteerDegrees);
  Vector3d forward = q.transform(new Vector3d(Math.sin(steer), 0, Math.cos(steer)));
  var propellers = nonRotorPropellers(v);
  // Buoyancy.isPropellerOperational is the exact pure predicate this selection applies - only
  // an operational propeller counts toward the shared thrust budget's denominator, so a
  // merely-active-but-unlinked, or linked-but-stalled, propeller does not dilute the share a
  // truly operational one gets just by existing on the chassis.
  var operational = propellers.stream().filter(p -> Buoyancy.isPropellerOperational(
      drivenBy.containsKey(p), p.isActiveVar.isActive, ((PartPropellerAccessor)(Object)p).div$currentRPM()))
    .toList();
  double perPropellerForce = Buoyancy.perOperationalPropellerForce(tuning.propellerForce, operational.size());
  for (var propeller : operational) {
   Vector3d anchor = new Vector3d(propeller.localOffset.x, propeller.localOffset.y, propeller.localOffset.z).sub(center);
   Vector3d arm = q.transform(new Vector3d(anchor));
   Vector3d point = new Vector3d(pose.position()).add(arm);
   boolean submerged = point.y <= waterSurfaceAt(level, point.x, point.z, point.y + 2);
   double thrust = Buoyancy.propellerThrust(drivenBy.get(propeller), perPropellerForce, submerged, true);
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

 /** Spares (MTS's own VehicleGroundDeviceBox excludes them from support the same way) carry
  * no hull support of their own - counting one would both activate buoyancy at its spare
  * mounting location and dilute volumePerFloat away from the floats actually holding the hull
  * up. */
 private static List<PartGroundDevice> floats(EntityVehicleF_Physics v) {
  return v.allParts.stream().filter(p -> p instanceof PartGroundDevice g && !g.isSpare && g.definition != null
      && Classification.isFloatDevice(g.definition.ground))
    .map(p -> (PartGroundDevice) p).toList();
 }

 /** Excludes any spare propeller (same reasoning as {@link #floats}) and any installed rotor
  * propeller via {@link Classification#needsUnderwaterPropellerThrust} - see the class-level
  * note on why rotor thrust is already supplied by {@code PhysicsBridge.Body.stepRotor} and
  * must not be counted twice. */
 private static List<PartPropeller> nonRotorPropellers(EntityVehicleF_Physics v) {
  return v.allParts.stream().filter(p -> p instanceof PartPropeller pr && !pr.isSpare
      && Classification.needsUnderwaterPropellerThrust(pr.definition != null ? pr.definition.propeller : null))
    .map(p -> (PartPropeller) p).toList();
 }
}
