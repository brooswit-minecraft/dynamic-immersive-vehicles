package io.github.brooswitminecraft.div;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.object.box.BoxPhysicsObject;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import io.github.brooswitminecraft.div.mixin.WorldAccessor;
import minecrafttransportsimulator.entities.instances.EntityVehicleF_Physics;
import minecrafttransportsimulator.entities.instances.PartEngine;
import minecrafttransportsimulator.entities.instances.PartGroundDevice;
import minecrafttransportsimulator.jsondefs.JSONVehicle;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** One server-owned Sable body. IV retains models, seats, controls, persistence and packets. */
public final class PhysicsBridge {
 private static final BodyRegistry<EntityVehicleF_Physics, Body> registry = new BodyRegistry<>();
 // Vehicle definitions whose chassis geometry failed to build a Sable body once already;
 // keyed by the shared JSON definition object (one per packID:systemName, not per entity), so a
 // bad content-pack definition is retried, and logged, once rather than every tick for every
 // vehicle instance of that definition.
 private static final Set<JSONVehicle> brokenDefinitions = Collections.newSetFromMap(new IdentityHashMap<>());
 private static final double DT = .05;
 private PhysicsBridge() {}
 private static Overrides.Tuning tuning(JSONVehicle definition) {
  var t = Overrides.get(definition.packID + ":" + definition.systemName);
  return t != null ? t : Overrides.DEFAULT;
 }
 /** Any ground-contact device (wheel or track) the generalized suspension/friction solver can drive. */
 private static List<PartGroundDevice> groundContacts(EntityVehicleF_Physics v) {
  return v.allParts.stream().filter(p -> p instanceof PartGroundDevice g && g.definition != null
      && Classification.isDrivableGroundDevice(g.definition.ground))
    .map(p -> (PartGroundDevice)p).toList();
 }
 public static boolean active(EntityVehicleF_Physics v) {
  // Guard every dereference used to decide eligibility, in entry order, before Classification
  // (or anything else) ever sees them: a vehicle with no definition, or with a ground part that
  // itself has no definition, must be excluded here rather than throw.
  JSONVehicle definition = v.definition;
  if (definition == null) return deactivate(v);
  String key = definition.packID + ":" + definition.systemName;
  var installedGroundDevices = v.allParts.stream()
    .filter(p -> p instanceof PartGroundDevice g && g.definition != null)
    .map(p -> ((PartGroundDevice)p).definition.ground).toList();
  boolean eligible = Classification.eligible(definition, installedGroundDevices, v.towedByConnection != null, Overrides.isOptedOut(key))
    && !brokenDefinitions.contains(definition);
  if (!eligible) return deactivate(v);
  if (v.world.isClient()) return true;
  var level = ((WorldAccessor)v.world).div$level();
  if (!(level instanceof ServerLevel server) || SubLevelContainer.getContainer(server) == null) return false;
  return activateOrClassify(registry, brokenDefinitions, v, definition, () -> new Body(server,v,tuning(definition)));
 }
 private static boolean deactivate(EntityVehicleF_Physics v) {
  Body old = registry.deactivate(v);
  if (old != null) old.remove();
  return false;
 }
 /**
  * The exact classification active() applies to a construction failure, as a seam testable
  * with fake keys/a fake body/a fake constructor: only {@link InvalidChassisGeometry} - Body's
  * own explicit, pre-registration chassis-bounds check, thrown before any Sable object exists -
  * is safe to cache in {@code broken} as deterministic for {@code brokenKey}. Anything else
  * (including an unrelated IllegalArgumentException a Sable/world API might throw after
  * registration, already rolled back by the constructor itself) is left retryable rather than
  * silently poisoning every future instance of an otherwise-valid definition until restart.
  * {@code key} (the live entity, for the active-bodies registry) and {@code brokenKey} (the
  * shared definition, so a bad content-pack definition is cached once, not per entity) are
  * deliberately separate type parameters: PhysicsBridge keys these two maps differently.
  */
 static <K, C, B> boolean activateOrClassify(BodyRegistry<K, B> registry, Set<C> broken, K key, C brokenKey, Supplier<B> construct) {
  try {
   return registry.activateOnce(key, construct);
  } catch (InvalidChassisGeometry geometryInvalid) {
   broken.add(brokenKey);
   return false;
  } catch (RuntimeException transientFailure) {
   return false;
  }
 }
 public static void step(EntityVehicleF_Physics vehicle) { registry.get(vehicle).step(vehicle); }
 public static void cleanup() {
  registry.removeIf((v, b) -> !v.isValid, Body::remove);
 }
 public static void unload(Object level) {
  registry.removeIf((v, b) -> b.level == level, Body::remove);
 }
 private static Vec3 vec(Vector3d v) { return new Vec3(v.x,v.y,v.z); }
 private static final class Body {
  final ServerLevel level;
  final Overrides.Tuning tuning;
  final BoxPhysicsObject box;
  final RigidBodyHandle handle;
  final Vector3d center;
  final double mass;
  Body(ServerLevel level, EntityVehicleF_Physics v, Overrides.Tuning tuning) {
   this.level=level; this.tuning=tuning;
   mass=tuning.massKg != null ? tuning.massKg : Math.max(50,v.getMass());
   Vector3d min=new Vector3d(Double.POSITIVE_INFINITY), max=new Vector3d(Double.NEGATIVE_INFINITY);
   for (var group : v.definition.collisionGroups) for (var c : group.collisions) {
    min.min(new Vector3d(c.pos.x-c.width/2,c.pos.y-c.height/2,c.pos.z-c.width/2));
    max.max(new Vector3d(c.pos.x+c.width/2,c.pos.y+c.height/2,c.pos.z+c.width/2));
   }
   if (!Double.isFinite(min.x) || max.x<=min.x || max.y<=min.y || max.z<=min.z)
    throw new InvalidChassisGeometry("Vehicle has no finite chassis collision volume");
   center=new Vector3d(min).add(max).mul(.5);
   Vector3d half=new Vector3d(max).sub(min).mul(.5);
   var r=v.orientation;
   Quaterniond q=new Quaterniond().setFromNormalized(new Matrix3d(r.m00,r.m10,r.m20,r.m01,r.m11,r.m21,r.m02,r.m12,r.m22));
   Vector3d position=q.transform(new Vector3d(center)).add(v.position.x,v.position.y,v.position.z);
   box=new BoxPhysicsObject(new Pose3d(position,q,new Vector3d(),new Vector3d(1)),half,mass);
   var physics=SubLevelContainer.getContainer(level).physicsSystem();
   physics.addObject(box);
   // From here on, box is a live Sable object; any failure before this constructor returns
   // must remove it again, since nothing else will ever hold a reference to clean it up.
   RigidBodyHandle h;
   try {
    h=RigidBodyHandle.of(level,box);
    h.addLinearAndAngularVelocity(new Vector3d(v.motion.x,v.motion.y,v.motion.z).mul(v.speedFactor*20),new Vector3d());
   } catch (RuntimeException ex) {
    physics.removeObject(box);
    throw ex;
   }
   handle=h;
  }
  void remove() { var c=SubLevelContainer.getContainer(level); if(c!=null)c.physicsSystem().removeObject(box); }
  void step(EntityVehicleF_Physics v) {
   box.updatePose();
   Pose3d pose=new Pose3d(box.getPose());
   Quaterniond q=new Quaterniond(pose.orientation()), inverse=new Quaterniond(q).invert();
   Vector3d velocity=handle.getLinearVelocity(new Vector3d()), angular=handle.getAngularVelocity(new Vector3d());
   Vector3d up=q.transform(new Vector3d(0,1,0));
   double drive=0;
   for(var part:v.allParts) if(part instanceof PartEngine engine && engine.running)
    drive=Math.signum(engine.currentGearVar.currentValue)*v.throttleVar.currentValue*tuning.driveForce;
   boolean braking=v.brakeVar.isActive || v.parkingBrakeVar.isActive;
   // Tracked vehicles steer by differential track speed, not a steerable wheel; cars/trucks
   // keep steering the wheels marked turnsWithSteer. Both can coexist on the same chassis.
   boolean skidSteer=v.definition.motorized != null && v.definition.motorized.hasSkidSteer;
   var wheels=groundContacts(v);
   double spring=mass*9.81/(wheels.size()*tuning.suspensionTravel*.5);
   double damper=2*Math.sqrt(spring*mass/wheels.size())*.7;
   boolean contact=false;
   for(var wheel:wheels) {
    double radius=wheel.getHeight()/2;
    Vector3d anchor=new Vector3d(wheel.localOffset.x,wheel.localOffset.y+tuning.suspensionTravel*.5,wheel.localOffset.z).sub(center);
    Vector3d arm=q.transform(new Vector3d(anchor));
    Vector3d start=new Vector3d(pose.position()).add(arm);
    double length=radius+tuning.suspensionTravel;
    Vector3d end=new Vector3d(start).fma(-length,up);
    var hit=level.clip(new ClipContext(vec(start),vec(end),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,(net.minecraft.world.entity.Entity)null));
    if(hit.getType()==HitResult.Type.MISS) { wheel.contactThisTick=false; continue; }
    double compression=length-hit.getLocation().distanceTo(vec(start));
    Vector3d localVelocity=new Vector3d(angular).cross(arm).add(velocity);
    double normal=Math.min(mass*9.81,TireForces.suspension(compression,localVelocity.dot(up),spring,damper));
    double steer=wheel.placementDefinition.turnsWithSteer ? (-v.rudderInputVar.currentValue / EntityVehicleF_Physics.MAX_RUDDER_ANGLE)*Math.toRadians(tuning.maxSteerDegrees) : 0;
    Vector3d forward=q.transform(new Vector3d(Math.sin(steer),0,Math.cos(steer)));
    Vector3d right=new Vector3d(up).cross(forward);
    double speed=localVelocity.dot(forward), sideSpeed=localVelocity.dot(right);
    double longitudinal=braking ? -Math.signum(speed)*Math.min(tuning.brakeForce/wheels.size(),Math.abs(speed)*mass/wheels.size()/DT) : drive/wheels.size();
    if (skidSteer) longitudinal*=1-Math.signum(anchor.x)*(v.rudderInputVar.currentValue/EntityVehicleF_Physics.MAX_RUDDER_ANGLE);
    longitudinal-=speed*20;
    var tire=TireForces.friction(longitudinal,-sideSpeed*mass/wheels.size()/DT,normal,tuning.friction);
    Vector3d force=new Vector3d(up).mul(normal).fma(tire.longitudinal(),forward).fma(tire.lateral(),right);
    Vector3d point=new Vector3d(hit.getLocation().x,hit.getLocation().y,hit.getLocation().z).sub(pose.position());
    handle.applyImpulseAtPoint(inverse.transform(point),inverse.transform(force.mul(DT)));
    wheel.contactThisTick=true;
    wheel.animateAsOnGround=true;
    wheel.angularVelocity=speed*DT/(Math.max(.05,radius)*2*Math.PI);
    contact=true;
   }
   if(contact || drive!=0) box.wakeUp();
   // Sable is authoritative. The pose here is the most recent completed simulation step.
   Vector3d origin=new Vector3d(pose.position()).sub(q.transform(new Vector3d(center)));
   v.position.set(origin.x,origin.y,origin.z);
   Matrix3d m=new Matrix3d().set(q);
   var r=v.orientation;
   r.m00=m.m00();r.m01=m.m10();r.m02=m.m20();
   r.m10=m.m01();r.m11=m.m11();r.m12=m.m21();
   r.m20=m.m02();r.m21=m.m12();r.m22=m.m22();r.convertToAngles();
   v.motion.set(velocity.x,velocity.y,velocity.z).scale(1/(20*v.speedFactor));
  }
 }
}
