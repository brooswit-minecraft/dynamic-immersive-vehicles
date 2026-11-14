package io.github.brooswitminecraft.div;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.object.box.BoxPhysicsObject;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import io.github.brooswitminecraft.div.experimental.TwoBodyChassis;
import net.minecraft.server.level.ServerLevel;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Two-half chassis construction/cleanup (ticket comment 53236/53240/53261's "body lifecycle/
 * cleanup" ask) - the Sable-touching counterpart to the pure math in
 * {@code io.github.brooswitminecraft.div.experimental.TwoBodyChassis}. Deliberately NOT wired
 * into {@code PhysicsBridge.Body} yet - this is the construction/teardown mechanism isolated and
 * reviewable on its own, per John's own explicit instruction not to deploy or call this path
 * validated without live motion/constraint evidence (which requires the dedicated anchor-frame
 * probe CI run this ticket is also running, not something a plain unit test can provide - the
 * same limitation every other Sable-touching code in this file already has, e.g.
 * {@code PhysicsBridge.createHitch} has no direct unit test either, only its pure
 * {@code HitchJoint} geometry helper does).
 *
 * <p>Mirrors the EXACT proven patterns already shipped in this file: box/handle creation matches
 * {@code PhysicsBridge.Body}'s own constructor (addObject, then RigidBodyHandle.of, with rollback
 * on any failure before returning); the joint uses the same {@code GenericConstraintConfiguration}/
 * {@code addConstraint} call shape {@code PhysicsBridge.createHitch} already uses for trailer
 * hitches, with the anchor/orientation CONVENTION confirmed correct for an ordinary
 * (non-sublevel) box by the real native source (ticket comment 53256: body-local
 * {@code pos1}/{@code pos2}, identity {@code orientation1}/{@code orientation2} for "no extra
 * twist") - the same convention MINECRAFT-525 applied to {@code createHitch} itself, which
 * previously used a world-space/echoed-orientation convention instead.
 */
public final class TwoBodyLifecycle {
 private TwoBodyLifecycle() {}

 /** Both half-bodies plus the joint connecting them - everything {@code Body} would need to
  * hold once this is wired in. {@code upperBox}/{@code lowerBox} and their handles are
  * independent Sable rigid bodies; {@code joint} is the single fixed constraint locking all 6
  * relative axes between them, with contacts already suppressed between the pair.
  *
  * <p>Public (same visibility rationale as {@code HitchJoint}): {@code TwoBodyImpulseResponseProbe}
  * (ticket comment 53280) needs to build the exact same production two-half chassis from the
  * {@code io.github.brooswitminecraft.div.experimental} package to empirically test the real
  * joint-coupled response, not a reimplementation of this construction. */
 public record Bodies(BoxPhysicsObject upperBox, BoxPhysicsObject lowerBox,
     RigidBodyHandle upperHandle, RigidBodyHandle lowerHandle, PhysicsConstraintHandle joint) {}

 /**
  * Constructs both half-bodies at their correct, geometry-preserving poses around {@code
  * centerPosition}/{@code centerOrientation} (the SAME single-body chassis center and
  * orientation {@code PhysicsBridge.Body}'s own constructor already computes), connects them
  * with a fixed joint using the confirmed body-local/identity-local-frame convention, and
  * disables contacts between the pair. On ANY failure partway through, rolls back everything
  * already created (mirrors {@code Body}'s own constructor rollback contract and {@code
  * reconcileHitch}'s "no partial state recorded for a failed attempt" guarantee) and rethrows -
  * never returns a partially-constructed {@code Bodies}.
  *
  * @param halfExtents the ORIGINAL single-body chassis half-extents (not halved) - each half's
  *  own half-extents are derived from this via {@code TwoBodyChassis.halfExtentsOfEachHalf}.
  */
 public static Bodies create(ServerLevel level, Vector3d centerPosition, Quaterniond centerOrientation,
     Vector3d halfExtents, double totalMassKg, TwoBodyChassis.Config config) {
  double halfHeightB = halfExtents.y;
  Vector3d eachHalfExtents = TwoBodyChassis.halfExtentsOfEachHalf(halfExtents);
  double upperMassKg = TwoBodyChassis.upperMassKg(totalMassKg, config);
  double lowerMassKg = TwoBodyChassis.lowerMassKg(totalMassKg, config);

  Vector3d upperPosition = new Vector3d(centerOrientation.transform(TwoBodyChassis.upperCenterOffsetFromG(halfHeightB))).add(centerPosition);
  Vector3d lowerPosition = new Vector3d(centerOrientation.transform(TwoBodyChassis.lowerCenterOffsetFromG(halfHeightB))).add(centerPosition);

  var physics = SubLevelContainer.getContainer(level).physicsSystem();
  BoxPhysicsObject upperBox = new BoxPhysicsObject(new Pose3d(upperPosition, new Quaterniond(centerOrientation), new Vector3d(), new Vector3d(1)), eachHalfExtents, upperMassKg);
  physics.addObject(upperBox);
  BoxPhysicsObject lowerBox;
  try {
   lowerBox = new BoxPhysicsObject(new Pose3d(lowerPosition, new Quaterniond(centerOrientation), new Vector3d(), new Vector3d(1)), eachHalfExtents, lowerMassKg);
   physics.addObject(lowerBox);
  } catch (RuntimeException ex) {
   physics.removeObject(upperBox);
   throw ex;
  }
  RigidBodyHandle upperHandle, lowerHandle;
  try {
   upperHandle = RigidBodyHandle.of(level, upperBox);
   lowerHandle = RigidBodyHandle.of(level, lowerBox);
  } catch (RuntimeException ex) {
   physics.removeObject(upperBox);
   physics.removeObject(lowerBox);
   throw ex;
  }
  // Body-local anchors, identity local joint frame - confirmed correct for an ordinary
  // (non-sublevel) box by the real native source (ticket comment 53256), the same convention
  // MINECRAFT-525 applied to PhysicsBridge.createHitch's own trailer-hitch joint.
  var config2 = new GenericConstraintConfiguration(
    TwoBodyChassis.jointAnchorLocalInUpperBody(halfHeightB), TwoBodyChassis.jointAnchorLocalInLowerBody(halfHeightB),
    new Quaterniond(), new Quaterniond(), HitchJoint.lockedAxes(true, false));
  PhysicsConstraintHandle joint = null;
  try {
   joint = physics.getPipeline().addConstraint(upperBox, lowerBox, config2);
   if (joint == null || !joint.isValid()) throw new IllegalStateException("two-body fixed joint construction failed or returned invalid");
   joint.setContactsEnabled(false);
  } catch (RuntimeException ex) {
   removeJoint(joint);
   // Review 53272: attempt BOTH removals even if the first one itself throws - an exception
   // from removing upperBox must not prevent the attempt to remove lowerBox too.
   removeBoth(physics, upperBox, lowerBox);
   throw ex;
  }
  return new Bodies(upperBox, lowerBox, upperHandle, lowerHandle, joint);
 }

 /** Explicitly retire the constraint before its bodies. Live mode-transition validation on
  * Sable 2.0.5 found the native joint handle could remain valid after both boxes were removed. */
 public static void remove(ServerLevel level, Bodies bodies) {
  var container = SubLevelContainer.getContainer(level);
  if (container == null) return;
  removeJoint(bodies.joint());
  removeBoth(container.physicsSystem(), bodies.upperBox(), bodies.lowerBox());
 }

 private static void removeJoint(PhysicsConstraintHandle joint) {
  if (joint == null) return;
  try { if (joint.isValid()) joint.remove(); }
  catch (RuntimeException ex) { LOGGER.error("[DIV twoBodyLifecycle] failed to remove fixed joint", ex); }
 }

 /** Review 53272's explicit "ensure cleanup attempts both halves even if removing one fails" -
  * each removal is independently attempted/caught so a RuntimeException from one can never skip
  * the other. Logging (not rethrowing) matches this being best-effort cleanup, not an operation
  * whose own failure should mask whatever original error triggered it. */
 private static void removeBoth(dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem physics, BoxPhysicsObject upperBox, BoxPhysicsObject lowerBox) {
  try { physics.removeObject(upperBox); } catch (RuntimeException ex) { LOGGER.error("[DIV twoBodyLifecycle] failed to remove upper half", ex); }
  try { physics.removeObject(lowerBox); } catch (RuntimeException ex) { LOGGER.error("[DIV twoBodyLifecycle] failed to remove lower half", ex); }
 }
 private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
}
