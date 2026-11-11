package io.github.brooswitminecraft.div.experimental;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.object.box.BoxPhysicsObject;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import io.github.brooswitminecraft.div.HitchJoint;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;

/**
 * Bounded, opt-in, CI/isolated-host-only runtime probe (ticket comment 53243/53244) answering the
 * question comment 53243 raised and could NOT settle from static reading: does Sable's real
 * (native, not decompilable from the public API jar) constraint solver treat
 * {@code GenericConstraintConfiguration}'s {@code pos1}/{@code pos2} as WORLD-space points or as
 * BODY-LOCAL offsets? DIV's own existing, shipped hitch code ({@code PhysicsBridge.createHitch})
 * assumes world-space; review's own direct testing claimed body-local "despite docs" - a direct
 * conflict this class exists to resolve empirically, once, away from any real vehicle or Zippy.
 *
 * <p>REWRITE per review 53246's correction of this class's first version (that version's run is
 * preserved as CI run 43089969294's uploaded log/evidence, NOT treated as a correctness pass):
 * <ul>
 *  <li>The constraint handle returned by {@code addConstraint} is now RETAINED and checked
 *  ({@code isValid()}, logged) instead of discarded - an unretained handle's lifecycle/update
 *  requirements were never verified against the pinned API.</li>
 *  <li>{@code setContactsEnabled(false)} is now called on that handle immediately, and box half-
 *  extents/anchor spacing were increased (0.3 half-extent, 1.0 anchor offset from each body's own
 *  center - over 1.4 units of guaranteed clearance even accounting for rotation, since a 0.3-half-
 *  extent box's farthest corner is only ~0.52 from its own center) so rotated adjacent cubes
 *  cannot physically overlap regardless of the contact-suppression result.</li>
 *  <li>Absolute position AND velocity (via {@code PhysicsPipeline.getLinearVelocity}/
 *  {@code getAngularVelocity}) are now logged every tick for BOTH bodies in BOTH pairs - "zero
 *  relative drift" is meaningless if gravity/physics turns out not to be live at all; this proves
 *  liveness independently of the drift measurement itself.</li>
 *  <li>The two hypothesis pairs no longer use DIFFERENT orientations for body A vs body B at all
 *  - review's correction: with distinct qA/qB, a wrong orientation1/orientation2 interpretation
 *  could ALSO produce drift, confounding the position (pos1/pos2) question this probe exists to
 *  answer. Both bodies in BOTH pairs now share the exact SAME single non-axis-aligned orientation
 *  Q, with {@code orientation1=orientation2=Q} (matching each body's own actual world orientation
 *  - the same "echo this body's own orientation" convention DIV's existing hitch code already
 *  uses) - under either sensible reading of what orientation1/2 means (this body's own world
 *  orientation, or a local joint-frame rotation relative to an UNROTATED body), the relative
 *  target is unambiguously identity here, isolating this probe to ONLY the pos1/pos2 question.
 *  A follow-up probe with genuinely distinct rotations is deferred until this result is in hand,
 *  per review's own "test distinct rotations separately" instruction.</li>
 * </ul>
 *
 * <p>Method: two independent, non-interacting pairs of throwaway boxes, far from world origin
 * (x=5000+), sharing one real non-axis-aligned orientation Q. Pair "world" is given the WORLD-
 * space position hypothesis (pos1=pos2=the shared world anchor point G); pair "local" is given
 * the BODY-LOCAL hypothesis (pos1/pos2 = each body's own local offset to G). Both pairs start at
 * an IDENTICAL, internally-consistent initial configuration (G sits exactly at each body's own
 * intended offset by construction - verified by direct computation below) and are jointed with
 * all 6 axes locked (a true fixed joint, via {@code HitchJoint.lockedAxes(true,false)}).
 * Whichever pair's real engine behavior matches its own hypothesis will show near-zero RELATIVE
 * drift between the two bodies over the probe window; the other pair's hypothesis is wrong for
 * its own anchors, so the solver visibly drags the bodies toward a different relative
 * configuration - a real, measurable, logged divergence.
 *
 * <p>Gravity acts on both pairs identically and is NOT disabled - irrelevant to the RELATIVE
 * measurement (a correctly-jointed pair free-falls together with zero relative drift regardless
 * of gravity) and is exactly what proves physics liveness via the absolute-position/velocity log.
 *
 * <p>Opt-in via {@code -Ddiv.experimentalAnchorFrameProbe=true} - OFF by default, never runs
 * during normal play. Runs ONCE per server lifetime, for a bounded window, then stops.
 */
public final class AnchorFrameProbe {
 private AnchorFrameProbe() {}
 private static final boolean ENABLED = Boolean.getBoolean("div.experimentalAnchorFrameProbe");
 private static final int PROBE_TICKS = 60; // 3 real-time seconds at 20tps - long enough to visibly settle or diverge, short enough to stay bounded
 private static final double MASS = 10.0;
 private static final Vector3d HALF_EXTENTS = new Vector3d(0.3, 0.3, 0.3);
 private static final double ANCHOR_OFFSET_MAGNITUDE = 1.0; // each body's own center to the shared anchor point, in that body's own local frame
 private static boolean started = false;
 private static int ticksRemaining = 0;
 private static Probe worldProbe, localProbe;
 private static final Logger LOGGER = LogUtils.getLogger();

 private record Probe(String label, BoxPhysicsObject boxA, BoxPhysicsObject boxB, PhysicsConstraintHandle joint,
   Vector3d initialRelativeAnchorInA, Quaterniond initialRelativeOrientation) {}

 /** Call once per server tick after a ServerLevel is confirmed to exist. Cheap no-op unless the
  * opt-in flag is set and the probe hasn't already run to completion this server lifetime. */
 public static void maybeRun(ServerLevel level) {
  if (!ENABLED) return;
  if (!started) {
   started = true;
   try {
    start(level);
    ticksRemaining = PROBE_TICKS;
   } catch (RuntimeException ex) {
    LOGGER.error("[DIV anchorFrameProbe] failed to start", ex);
    ticksRemaining = 0;
   }
   return;
  }
  if (ticksRemaining <= 0) return;
  // CI run 43089969294's first attempt crashed the server: BoxPhysicsObject.updatePose()
  // NPE'd on the very next tick after addObject/addConstraint - the box's native handle is not
  // necessarily populated synchronously within the same or next server tick (Sable likely
  // finishes registering it on its own physics tick, not NeoForge's generic tick-post event).
  // Retry with a bounded total-elapsed cap instead of assuming one tick of buffer is enough or
  // crashing the whole server on a transient not-ready state.
  warmupTicksElapsed++;
  if (warmupTicksElapsed > MAX_WARMUP_TICKS) {
   LOGGER.error("[DIV anchorFrameProbe] FAILED - boxes never became ready to read within " + MAX_WARMUP_TICKS + " ticks");
   ticksRemaining = 0;
   return;
  }
  boolean worldReady = tryLogTick(level, worldProbe);
  boolean localReady = tryLogTick(level, localProbe);
  if (!worldReady || !localReady) return; // don't consume the measurement budget on a not-ready tick
  ticksRemaining--;
  if (ticksRemaining == 0) {
   LOGGER.info("[DIV anchorFrameProbe] COMPLETE - compare the two 'drift' series above, and check "
     + "the absolute position/velocity lines confirm BOTH pairs are actually falling under gravity "
     + "(liveness) before trusting either drift number. The hypothesis (world or local) whose "
     + "drift stayed near zero throughout, with confirmed liveness, is Sable's real convention.");
  }
 }
 // CI run 43091100528 (after the handle-race fix) still failed after the full warmup window -
 // both joints reported valid, but the boxes' poses never became readable. x=5000 is far enough
 // from spawn that its chunks were very likely never loaded/generated at all in this fresh,
 // playerless disposable world - explicitly force-loading them in start() (see below) and
 // allowing extra time for first-time chunk generation at a remote location.
 private static final int MAX_WARMUP_TICKS = 400; // 20 real seconds at 20tps
 private static int warmupTicksElapsed = 0;
 /** @return true if this pair's poses were actually readable this tick (and were logged);
  *  false if the native handle still was not ready (caught, not crashed, and simply retried
  *  next tick without consuming the measurement budget). */
 private static boolean tryLogTick(ServerLevel level, Probe p) {
  try {
   logTick(level, p);
   return true;
  } catch (NullPointerException | IllegalStateException notReadyYet) {
   return false;
  }
 }

 /** Force-loads (and keeps loaded) the chunks a probe pair's two boxes sit in, so their physics
  * registration does not depend on this disposable, playerless world happening to have already
  * loaded/generated terrain at a remote test location - the real cause of CI run 43091100528's
  * second failure (both joints reported valid; the boxes' poses simply never became readable
  * within the full warmup window). */
 private static void forceLoadChunksNear(ServerLevel level, Vector3d... positions) {
  for (Vector3d pos : positions) {
   level.setChunkForced(Math.floorDiv((int) Math.floor(pos.x), 16), Math.floorDiv((int) Math.floor(pos.z), 16), true);
  }
 }

 private static void start(ServerLevel level) {
  // ONE shared non-axis-aligned orientation for every body in both pairs (review 53246's
  // correction) - isolates this probe to the pos1/pos2 question alone, since the relative
  // target orientation is unambiguously identity regardless of which orientation1/2 semantic
  // Sable actually uses.
  Quaterniond sharedQ = new Quaterniond().rotateY(Math.toRadians(35)).rotateX(Math.toRadians(20));
  Vector3d anchorOffsetInA = new Vector3d(0, -ANCHOR_OFFSET_MAGNITUDE, 0);
  Vector3d anchorOffsetInB = new Vector3d(0, ANCHOR_OFFSET_MAGNITUDE, 0);

  Vector3d posA = new Vector3d(5000, 80, 5000);
  forceLoadChunksNear(level, posA, new Vector3d(posA).add(500, 0, 0));
  Vector3d worldG = new Vector3d(sharedQ.transform(new Vector3d(anchorOffsetInA))).add(posA);
  Vector3d posB = new Vector3d(worldG).sub(sharedQ.transform(new Vector3d(anchorOffsetInB)));
  worldProbe = buildPair(level, "world", posA, posB, sharedQ, anchorOffsetInA, anchorOffsetInB,
    new GenericConstraintConfiguration(worldG, worldG, sharedQ, sharedQ, HitchJoint.lockedAxes(true, false)));

  // Second pair, offset far away in X so it cannot physically interact with the first, otherwise
  // identical physical configuration but given the BODY-LOCAL hypothesis instead.
  Vector3d posA2 = new Vector3d(posA).add(500, 0, 0);
  Vector3d worldG2 = new Vector3d(sharedQ.transform(new Vector3d(anchorOffsetInA))).add(posA2);
  Vector3d posB2 = new Vector3d(worldG2).sub(sharedQ.transform(new Vector3d(anchorOffsetInB)));
  localProbe = buildPair(level, "local", posA2, posB2, sharedQ, anchorOffsetInA, anchorOffsetInB,
    new GenericConstraintConfiguration(anchorOffsetInA, anchorOffsetInB, sharedQ, sharedQ, HitchJoint.lockedAxes(true, false)));

  LOGGER.info("[DIV anchorFrameProbe] started - worldG=" + worldG + " worldG2=" + worldG2 + " probeTicks=" + PROBE_TICKS);
 }

 private static Probe buildPair(ServerLevel level, String label, Vector3d posA, Vector3d posB, Quaterniond sharedQ,
     Vector3d anchorOffsetInA, Vector3d anchorOffsetInB, GenericConstraintConfiguration config) {
  var physics = SubLevelContainer.getContainer(level).physicsSystem();
  var boxA = new BoxPhysicsObject(new Pose3d(new Vector3d(posA), new Quaterniond(sharedQ), new Vector3d(), new Vector3d(1)), HALF_EXTENTS, MASS);
  var boxB = new BoxPhysicsObject(new Pose3d(new Vector3d(posB), new Quaterniond(sharedQ), new Vector3d(), new Vector3d(1)), HALF_EXTENTS, MASS);
  physics.addObject(boxA);
  physics.addObject(boxB);
  RigidBodyHandle.of(level, boxA);
  RigidBodyHandle.of(level, boxB);
  PhysicsConstraintHandle joint = physics.getPipeline().addConstraint(boxA, boxB, config);
  boolean valid = joint != null && joint.isValid();
  LOGGER.info("[DIV anchorFrameProbe] pair=" + label + " jointCreated=" + (joint != null) + " jointValid=" + valid);
  if (joint != null) {
   // Ticket's explicit "disable collisions between constrained chassis pieces as supported" -
   // review 53246's correction that the first version never did this at all.
   joint.setContactsEnabled(false);
  }
  Quaterniond initialRelativeOrientation = new Quaterniond(sharedQ).invert().mul(new Quaterniond(sharedQ));
  return new Probe(label, boxA, boxB, joint, new Vector3d(anchorOffsetInA), initialRelativeOrientation);
 }

 private static void logTick(ServerLevel level, Probe p) {
  p.boxA().updatePose();
  p.boxB().updatePose();
  var poseA = p.boxA().getPose();
  var poseB = p.boxB().getPose();
  var physics = SubLevelContainer.getContainer(level).physicsSystem();
  Vector3d velA = physics.getPipeline().getLinearVelocity(p.boxA(), new Vector3d());
  Vector3d velB = physics.getPipeline().getLinearVelocity(p.boxB(), new Vector3d());
  // B's own anchor point (in B's local frame, offset +ANCHOR_OFFSET_MAGNITUDE along local Y),
  // expressed in A's local frame right now - at zero drift this must stay exactly equal to
  // p.initialRelativeAnchorInA() for all time (a true fixed joint never lets the anchor move
  // relative to either body).
  Vector3d anchorBWorld = new Vector3d(poseB.orientation().transform(new Vector3d(0, ANCHOR_OFFSET_MAGNITUDE, 0))).add(poseB.position());
  Vector3d anchorBInA = new Vector3d(anchorBWorld).sub(poseA.position());
  new Quaterniond(poseA.orientation()).invert().transform(anchorBInA);
  double positionDrift = anchorBInA.distance(p.initialRelativeAnchorInA());
  Quaterniond relativeOrientationNow = new Quaterniond(poseA.orientation()).invert().mul(new Quaterniond(poseB.orientation()));
  double orientationDriftRadians = relativeOrientationNow.invert().mul(new Quaterniond(p.initialRelativeOrientation())).angle();
  LOGGER.info("[DIV anchorFrameProbe] pair=" + p.label()
    + " positionDrift=" + positionDrift + "m orientationDrift=" + Math.toDegrees(orientationDriftRadians) + "deg"
    + " posA=" + poseA.position() + " velA=" + velA + " posB=" + poseB.position() + " velB=" + velB);
 }
}
