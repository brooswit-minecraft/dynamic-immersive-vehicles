package io.github.brooswitminecraft.div;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.object.box.BoxPhysicsObject;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import net.minecraft.server.level.ServerLevel;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * A float-only hull (boat/pontoon, Classification.floatEligible) - no wheel/track at all, so its
 * entire domain is {@link BuoyantForces}. An amphibious vehicle (wheel/track AND a canFloat
 * device) instead stays on PhysicsBridge's ground Body, which applies the same BuoyantForces
 * alongside its wheel forces - see Classification.floatEligible's javadoc for why this class is
 * the exclusive fallback for a hull with nothing the ground solver can drive, not a general
 * "has a float device" body.
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

 public void step(EntityVehicleF_Physics v) {
  box.updatePose();
  Pose3d pose = new Pose3d(box.getPose());
  Quaterniond q = new Quaterniond(pose.orientation()), inverse = new Quaterniond(q).invert();
  Vector3d velocity = handle.getLinearVelocity(new Vector3d()), angular = handle.getAngularVelocity(new Vector3d());
  boolean awake = BuoyantForces.apply(level, v, tuning, center, hullVolume, pose, q, inverse, velocity, angular, handle);
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
