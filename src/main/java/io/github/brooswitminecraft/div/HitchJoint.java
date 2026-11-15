package io.github.brooswitminecraft.div;
import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import minecrafttransportsimulator.baseclasses.RotationMatrix;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import java.util.EnumSet;
import java.util.Set;

/**
 * Pure geometry/axis-selection for a trailer hitch: where the joint anchors sit relative to each
 * body's own Sable origin, which local joint-frame rotation each side imposes, and which degrees
 * of freedom IV's own connection flags imply should be locked. Operates only on plain joml/MTS
 * data types (no live entity, world, or Sable runtime needed to test); PhysicsBridge supplies
 * the actual body centers and JSONConnection fields.
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
  * Converts one hitch connection's {@code JSONConnection.rot} to a joml {@code Quaterniond},
  * for use as one side of {@code GenericConstraintConfiguration}'s {@code local_frame1}/
  * {@code local_frame2}. Review finding (comment 53817): the decompiled, pinned IV24
  * {@code JSONConnection.rot} field is explicitly documented as "Used on mounted and restricted
  * connections. Has no effect on other connections" - so an ordinary (unmounted, unrestricted)
  * connection correctly stays IDENTITY regardless of {@code rot} (nothing is locked for it, so
  * the local-frame choice cannot matter), but a mounted/restricted connection's {@code rot} - if
  * authored - IS the frame {@link #lockedAxes}'s pitch/yaw/roll axis selection is actually
  * defined against, and must be honored rather than forced to identity. {@code rot==null}
  * (unset in the pack JSON) still means identity - the field's own documented default. Uses the
  * exact same {@code RotationMatrix}-&gt;{@code Quaterniond} conversion already established for a
  * vehicle's own world orientation in {@code PhysicsBridge.Body}'s constructor.
  *
  * <p>MINECRAFT-525 review 53832: this converter alone is NOT the full fix for the hookup side -
  * {@code PhysicsBridge.createHitch}'s own doc comment derives why {@code local_frame2} must be
  * this method's result INVERTED (not passed straight through) to match IV's native rotation
  * composition. {@code local_frame1} (the hitch side) needs no such inversion.
  */
 public static Quaterniond localJointFrame(RotationMatrix rot) {
  if (rot == null) return new Quaterniond();
  return new Quaterniond().setFromNormalized(new Matrix3d(rot.m00, rot.m10, rot.m20, rot.m01, rot.m11, rot.m21, rot.m02, rot.m12, rot.m22));
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
 /**
  * Composes a connection's own {@code pos} - authored in the connection's OWNING entity's local
  * frame, per {@code AJSONInteractableEntity.connections}'s shared base (both a vehicle's own
  * root JSON and an installed part's own JSON can define connections) - into the vehicle-root-
  * local frame {@link #anchorLocalOffset} needs, for the case left unverified/unsupported by
  * MINECRAFT-525 (see {@code PhysicsBridge.createHitch}'s own doc comment): the owning entity is
  * an installed {@code APart} rather than the vehicle's own root. {@code partLocalOffset}/
  * {@code partLocalOrientation} are {@code APart}'s own public fields, already fully resolved to
  * vehicle-root-local by MTS itself (APart composes them recursively through any of its own
  * sub-parts before DIV ever sees them - confirmed by the real {@code APart.update()} source,
  * and already relied on elsewhere in this codebase: {@code PhysicsBridge}'s wheel load-share
  * computation uses {@code wheel.localOffset} directly against the same body {@code center}
  * {@link #anchorLocalOffset}'s caller uses, with no extra transform). {@code partScale} is the
  * part's own full accumulated scale ({@code APart.scale}, the {@code AEntityC_Renderable} field
  * - includes both the parent's scale and the part's own {@code partScale}) - applied to the
  * connection's positional offset only, mirroring how {@code APart} itself scales its OWN
  * placement offset before adding its own position (its {@code rot} carries no separate scale;
  * {@code JSONConnection} defines none either). A connection authored directly on the vehicle's
  * own root JSON (the ordinary, pre-MINECRAFT-530 case) passes an identity orientation, zero
  * offset and scale of 1 here, which collapses this to exactly {@code connectionLocalPos} -
  * the original, unconverted value {@code createHitch} always used for that case.
  */
 public static Vector3d partConnectionOffset(Vector3dc partLocalOffset, RotationMatrix partLocalOrientation, Vector3dc partScale, Vector3dc connectionLocalPos, Vector3d dest) {
  dest.set(connectionLocalPos.x()*partScale.x(), connectionLocalPos.y()*partScale.y(), connectionLocalPos.z()*partScale.z());
  localJointFrame(partLocalOrientation).transform(dest);
  return dest.add(partLocalOffset);
 }
 /**
  * Composes a connection's own {@code rot} with the installed part's own local-frame rotation it
  * is authored against, for the same owning-entity gap {@link #partConnectionOffset} closes. The
  * right-multiply order ({@code partLocalOrientation} first, {@code connectionRot} applied after
  * it) mirrors {@code RotationMatrix.multiply(other)}'s own {@code this = this*other} convention
  * - the exact operation {@code APart.update()} itself uses to compose its placement {@code rot}
  * onto its own local orientation, and the one {@code PhysicsBridge.createHitch}'s doc comment
  * already cites for IV's native composition. A vehicle-root-defined connection passes an
  * identity {@code partLocalOrientation}, collapsing this to exactly {@link #localJointFrame}'s
  * own plain conversion of {@code connectionRot} alone - unaffected for that case.
  */
 public static Quaterniond partConnectionRotation(RotationMatrix partLocalOrientation, RotationMatrix connectionRot) {
  return localJointFrame(partLocalOrientation).mul(localJointFrame(connectionRot));
 }
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
