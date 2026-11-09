package io.github.brooswitminecraft.div;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.object.box.BoxPhysicsObject;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.entities.instances.PartEngine;
import minecrafttransportsimulator.entities.instances.PartGroundDevice;
import minecrafttransportsimulator.entities.instances.PartPropeller;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import java.util.List;

/**
 * A float-only hull (boat/pontoon) - the buoyancy/water-drag/propeller-thrust counterpart to
 * PhysicsBridge's ground-suspension Body, for vehicles Classification.floatEligible() picks out
 * instead. Buoyancy and water drag are sampled per installed canFloat ground device, at that
 * device's own world position, which naturally produces a righting torque when one side of the
 * hull sits deeper than the other - no separate stability code needed. Propeller thrust is zero
 * unless the propeller's own position is underwater, matching a real propeller spinning free in
 * air producing no thrust.
 */
final class BuoyantBody implements VehicleBody {
 private final ServerLevel level;
 private final Overrides.Tuning tuning;
 private final BoxPhysicsObject box;
 private final RigidBodyHandle handle;
 private final Vector3d center;
 private final double hullVolume;

 BuoyantBody(ServerLevel level, EntityVehicleF_Physics v, Overrides.Tuning tuning) {
  this.level = level;
  this.tuning = tuning;
  double mass = tuning.massKg != null ? tuning.massKg : Math.max(50, v.getMass());
  Vector3d min = new Vector3d(Double.POSITIVE_INFINITY), max = new Vector3d(Double.NEGATIVE_INFINITY);
  for (var group : v.definition.collisionGroups) for (var c : group.collisions) {
   min.min(new Vector3d(c.pos.x - c.width / 2, c.pos.y - c.height / 2, c.pos.z - c.width / 2));
   max.max(new Vector3d(c.pos.x + c.width / 2, c.pos.y + c.height / 2, c.pos.z + c.width / 2));
  }
  if (!Double.isFinite(min.x) || max.x <= min.x || max.y <= min.y || max.z <= min.z)
   throw new InvalidChassisGeometry("Vehicle has no finite chassis collision volume");
  center = new Vector3d(min).add(max).mul(.5);
  Vector3d half = new Vector3d(max).sub(min).mul(.5);
  // A real hull is not a solid box; hullFillFactor is the fraction of the chassis bounding-box
  // volume the hull actually displaces when fully submerged.
  hullVolume = half.x * 2 * half.y * 2 * half.z * 2 * tuning.hullFillFactor;
  var r = v.orientation;
  Quaterniond q = new Quaterniond().setFromNormalized(new Matrix3d(r.m00, r.m10, r.m20, r.m01, r.m11, r.m21, r.m02, r.m12, r.m22));
  Vector3d position = q.transform(new Vector3d(center)).add(v.position.x, v.position.y, v.position.z);
  box = new BoxPhysicsObject(new Pose3d(position, q, new Vector3d(), new Vector3d(1)), half, mass);
  var physics = SubLevelContainer.getContainer(level).physicsSystem();
  physics.addObject(box);
  RigidBodyHandle h;
  try {
   h = RigidBodyHandle.of(level, box);
   h.addLinearAndAngularVelocity(new Vector3d(v.motion.x, v.motion.y, v.motion.z).mul(v.speedFactor * 20), new Vector3d());
  } catch (RuntimeException ex) {
   physics.removeObject(box);
   throw ex;
  }
  handle = h;
 }

 public Object level() { return level; }
 public void remove() { var c = SubLevelContainer.getContainer(level); if (c != null) c.physicsSystem().removeObject(box); }

 /** First water surface Y found scanning straight down from fromY, or NEGATIVE_INFINITY if none. */
 private double waterSurfaceAt(double x, double z, double fromY) {
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
      && g.definition.ground != null && g.definition.ground.canFloat)
    .map(p -> (PartGroundDevice) p).toList();
 }

 public void step(EntityVehicleF_Physics v) {
  box.updatePose();
  Pose3d pose = new Pose3d(box.getPose());
  Quaterniond q = new Quaterniond(pose.orientation()), inverse = new Quaterniond(q).invert();
  Vector3d velocity = handle.getLinearVelocity(new Vector3d()), angular = handle.getAngularVelocity(new Vector3d());
  var floats = floats(v);
  double volumePerFloat = floats.isEmpty() ? 0 : hullVolume / floats.size();
  boolean awake = false;
  for (var flt : floats) {
   double radius = Math.max(.1, flt.getHeight() / 2);
   Vector3d anchor = new Vector3d(flt.localOffset.x, flt.localOffset.y, flt.localOffset.z).sub(center);
   Vector3d arm = q.transform(new Vector3d(anchor));
   Vector3d point = new Vector3d(pose.position()).add(arm);
   double waterSurface = waterSurfaceAt(point.x, point.z, point.y + radius + 1);
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
   boolean submerged = point.y <= waterSurfaceAt(point.x, point.z, point.y + 2);
   double thrust = Buoyancy.propellerThrust(drive, tuning.propellerForce / propellerCount, submerged);
   if (thrust == 0) continue;
   awake = true;
   Vector3d force = new Vector3d(forward).mul(thrust);
   handle.applyImpulseAtPoint(inverse.transform(arm), inverse.transform(new Vector3d(force).mul(PhysicsBridge.DT)));
  }
  if (awake) box.wakeUp();
  // Sable is authoritative. The pose here is the most recent completed simulation step.
  Vector3d origin = new Vector3d(pose.position()).sub(q.transform(new Vector3d(center)));
  v.position.set(origin.x, origin.y, origin.z);
  Matrix3d m = new Matrix3d().set(q);
  var r = v.orientation;
  r.m00 = m.m00(); r.m01 = m.m10(); r.m02 = m.m20();
  r.m10 = m.m01(); r.m11 = m.m11(); r.m12 = m.m21();
  r.m20 = m.m02(); r.m21 = m.m12(); r.m22 = m.m22(); r.convertToAngles();
  v.motion.set(velocity.x, velocity.y, velocity.z).scale(1 / (20 * v.speedFactor));
 }
}
