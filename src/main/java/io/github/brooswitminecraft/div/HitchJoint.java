package io.github.brooswitminecraft.div;
import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import java.util.EnumSet;
import java.util.Set;

/**
 * Pure geometry/axis-selection for a trailer hitch: where the joint anchors sit in world space,
 * and which degrees of freedom IV's own connection flags imply should be locked. Operates only
 * on plain joml types, so it needs no live entity, world, or Sable runtime to test; PhysicsBridge
 * supplies the actual body poses and JSONConnection flags.
 */
public final class HitchJoint {
 private HitchJoint() {}
 /**
  * World position of a local-frame offset (e.g. a JSONConnection.pos, relative to the entity's
  * own origin) on a body whose Sable pose center-of-mass sits at {@code bodyPosition}/
  * {@code bodyOrientation} and whose geometric center - in that same entity-local frame - is
  * {@code bodyCenter}, the same frame PhysicsBridge.Body already uses for its chassis bounds.
  */
 public static Vector3d anchorWorldPosition(Vector3dc bodyPosition, Quaterniondc bodyOrientation, Vector3dc bodyCenter, Vector3dc localOffset, Vector3d dest) {
  dest.set(localOffset).sub(bodyCenter);
  bodyOrientation.transform(dest);
  return dest.add(bodyPosition);
 }
 /**
  * Degrees of freedom to lock for one hitch, from IV's own per-connection flags: linear motion
  * is always locked (the two anchor points stay coincident - a hitch is not a sliding coupling),
  * and angular freedom follows what the connection itself claims. A {@code mounted} connection
  * (e.g. a flatbed car-carrier) is fully rigid; a {@code restricted} one allows only pitch - its
  * own documented contract ("restricted to only change in pitch, and won't be allowed to yaw
  * back and forth", JSONConnection.restricted) - so yaw and roll are locked and pitch is left
  * free; an ordinary connection leaves all rotation free, matching a real ball/pintle hitch where
  * the trailer's own wheels and suspension keep it upright, not the joint.
  */
 public static Set<ConstraintJointAxis> lockedAxes(boolean mounted, boolean restricted) {
  var axes = EnumSet.of(ConstraintJointAxis.LINEAR_X, ConstraintJointAxis.LINEAR_Y, ConstraintJointAxis.LINEAR_Z);
  if (mounted) {
   axes.add(ConstraintJointAxis.ANGULAR_X);
   axes.add(ConstraintJointAxis.ANGULAR_Y);
   axes.add(ConstraintJointAxis.ANGULAR_Z);
  } else if (restricted) {
   // Pitch is local X, yaw is local Y, roll is local Z - the same forward(Z)/up(Y)/right(X)
   // convention PhysicsBridge.Body.step already uses.
   axes.add(ConstraintJointAxis.ANGULAR_Y);
   axes.add(ConstraintJointAxis.ANGULAR_Z);
  }
  return axes;
 }
}
