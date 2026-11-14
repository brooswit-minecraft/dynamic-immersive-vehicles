package io.github.brooswitminecraft.div;
import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import java.util.EnumSet;
import java.util.Set;

/**
 * Pure geometry/axis-selection for a trailer hitch: where the joint anchors sit relative to each
 * body's own Sable origin, and which degrees of freedom IV's own connection flags imply should
 * be locked. Operates only on plain joml types, so it needs no live entity, world, or Sable
 * runtime to test; PhysicsBridge supplies the actual body centers and JSONConnection flags.
 */
public final class HitchJoint {
 private HitchJoint() {}
 /**
  * {@code GenericConstraintConfiguration}'s {@code pos1}/{@code pos2} anchor for a local-frame
  * offset (e.g. a JSONConnection.pos, relative to the entity's own origin) on a body whose
  * geometric center - in that same entity-local frame - is {@code bodyCenter}, the same frame
  * PhysicsBridge.Body already uses for its chassis bounds. MINECRAFT-525: confirmed by the real
  * native source and the empirical anchor-frame probe (ticket comment 53256/53243) that Sable's
  * ordinary (non-sublevel) box joints take {@code pos1}/{@code pos2} as BODY-LOCAL offsets
  * (Rapier's {@code local_anchor = joint.pos - center_of_mass}, with {@code center_of_mass}
  * falling back to zero for a plain box) - NOT world-space points, which is what the previously
  * shipped {@code PhysicsBridge.createHitch} wrongly assumed. No orientation or body position is
  * needed here at all; the joint-frame rotation that convention also confirmed is local (not an
  * echo of the body's own world orientation) is supplied directly by callers as identity, the
  * same pattern {@code TwoBodyLifecycle.create} already established for the two-body chassis
  * joint.
  */
 public static Vector3d anchorLocalOffset(Vector3dc bodyCenter, Vector3dc localOffset, Vector3d dest) {
  return dest.set(localOffset).sub(bodyCenter);
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
