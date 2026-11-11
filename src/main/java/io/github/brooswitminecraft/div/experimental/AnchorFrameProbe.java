package io.github.brooswitminecraft.div.experimental;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
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
 * <p>History of fixes, each driven by a real CI run's actual evidence, not guessing:
 * <ul>
 *  <li>v1 (CI 43089969294) discarded the constraint handle entirely and never suppressed contacts
 *  - crashed outright: {@code BoxPhysicsObject.updatePose()} NPE'd the very next tick after
 *  {@code addObject}/{@code addConstraint}.</li>
 *  <li>v2 (CI 43091100528) retained+validated the joint, suppressed contacts, used one shared
 *  orientation for every body (isolating this probe to the pos1/pos2 question alone, per review
 *  53246), and added a bounded NPE-catching warmup retry - both joints reported created/valid,
 *  but the boxes' poses never became readable within the full warmup window.</li>
 *  <li>v3 (this version, per review 53251's correction): traced the ACTUAL registration path by
 *  decompiling the pinned jar rather than continuing to just extend warmup blindly.
 *  {@code RigidBodyHandle.of(level,body)} does nothing on its own (confirmed: a pure, side-effect-
 *  free wrapper) - discarding it was never the problem. The real registration is
 *  {@code SubLevelPhysicsSystem.addObject(object)} -&gt; {@code object.onAddition(physicsSystem)}
 *  -&gt; (for a box) {@code this.handle = physicsSystem.getPipeline().addBox(this)}, called
 *  SYNCHRONOUSLY inside {@code addObject} - but {@code addBox}'s real implementation is native/
 *  internal, not visible in the public API jar, and evidently CAN leave {@code handle} null with
 *  no exception. {@code BoxPhysicsObject.isActive()} is NOT a reliable readiness signal (set
 *  {@code true} unconditionally at the top of {@code onAddition}, before {@code addBox} even
 *  runs); the reliable public signal is {@code getRuntimeId()} (-1 exactly when the native handle
 *  is null). Since nothing retries a failed {@code addBox} automatically, this version explicitly
 *  polls {@code getRuntimeId()} and, if a box is not ready after a bounded number of ticks,
 *  removes and re-adds it (forcing a fresh {@code onAddition}/{@code addBox} attempt) rather than
 *  passively waiting on an attempt that already failed. The constraint itself is now built only
 *  AFTER both bodies in a pair are confirmed ready, not speculatively beforehand.</li>
 * </ul>
 *
 * <p>Method: two independent, non-interacting pairs of throwaway boxes, far from world origin
 * (x=5000+, chunks explicitly force-loaded), sharing one real non-axis-aligned orientation Q. Pair
 * "world" is given the WORLD-space position hypothesis (pos1=pos2=the shared world anchor point
 * G); pair "local" is given the BODY-LOCAL hypothesis (pos1/pos2 = each body's own local offset to
 * G). Both pairs start at an IDENTICAL, internally-consistent initial configuration (G sits
 * exactly at each body's own intended offset by construction) and are jointed with all 6 axes
 * locked (a true fixed joint, via {@code HitchJoint.lockedAxes(true,false)}), with contacts
 * suppressed between the pair. Whichever pair's real engine behavior matches its own hypothesis
 * will show near-zero RELATIVE drift between the two bodies over the probe window; the other
 * pair's hypothesis is wrong for its own anchors, so the solver visibly drags the bodies toward a
 * different relative configuration - a real, measurable, logged divergence.
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
 private static final int MAX_ACTIVATION_TICKS = 400; // 20 real seconds at 20tps to get both pairs' bodies+joint fully ready
 private static final int RETRY_INTERVAL_TICKS = 40; // 2s between re-registration attempts for a box that hasn't become ready
 private static final double MASS = 10.0;
 private static final Vector3d HALF_EXTENTS = new Vector3d(0.3, 0.3, 0.3);
 private static final double ANCHOR_OFFSET_MAGNITUDE = 1.0; // each body's own center to the shared anchor point, in that body's own local frame
 private static boolean started = false;
 private static boolean measuring = false;
 private static int activationTicksElapsed = 0;
 private static int ticksRemaining = 0;
 private static Probe worldProbe, localProbe;
 private static final Logger LOGGER = LogUtils.getLogger();

 /** Mutable (not a record): {@code joint} and {@code lastRetryTick} change as activation
  * progresses, which a record's immutability would make awkward to express honestly. */
 private static final class Probe {
  final String label;
  final BoxPhysicsObject boxA, boxB;
  final GenericConstraintConfiguration config;
  final Vector3d initialRelativeAnchorInA;
  final Quaterniond initialRelativeOrientation;
  PhysicsConstraintHandle joint;
  boolean constraintBuilt = false;
  int lastRetryTickA = 0, lastRetryTickB = 0;
  Probe(String label, BoxPhysicsObject boxA, BoxPhysicsObject boxB, GenericConstraintConfiguration config,
      Vector3d initialRelativeAnchorInA, Quaterniond initialRelativeOrientation) {
   this.label = label; this.boxA = boxA; this.boxB = boxB; this.config = config;
   this.initialRelativeAnchorInA = initialRelativeAnchorInA; this.initialRelativeOrientation = initialRelativeOrientation;
  }
  boolean bothBodiesReady() { return boxA.getRuntimeId() != -1 && boxB.getRuntimeId() != -1; }
 }

 /** Call once per server tick after a ServerLevel is confirmed to exist. Cheap no-op unless the
  * opt-in flag is set and the probe hasn't already run to completion this server lifetime. */
 public static void maybeRun(ServerLevel level) {
  if (!ENABLED) return;
  if (!started) {
   started = true;
   try {
    start(level);
   } catch (RuntimeException ex) {
    LOGGER.error("[DIV anchorFrameProbe] failed to start", ex);
    measuring = false;
    ticksRemaining = 0;
    activationTicksElapsed = MAX_ACTIVATION_TICKS + 1; // skip straight to the give-up path below
   }
   return;
  }
  if (!measuring) {
   activationTicksElapsed++;
   var physics = SubLevelContainer.getContainer(level).physicsSystem();
   boolean worldReady = activate(level, worldProbe);
   boolean localReady = activate(level, localProbe);
   if (worldReady && localReady) {
    measuring = true;
    ticksRemaining = PROBE_TICKS;
    LOGGER.info("[DIV anchorFrameProbe] both pairs fully activated after " + activationTicksElapsed + " ticks - beginning " + PROBE_TICKS + "-tick measurement");
   } else if (activationTicksElapsed > MAX_ACTIVATION_TICKS) {
    LOGGER.error("[DIV anchorFrameProbe] FAILED - pairs never became ready within " + MAX_ACTIVATION_TICKS + " ticks (worldReady=" + worldReady + " localReady=" + localReady + ")");
    ticksRemaining = 0;
    measuring = true; // stop retrying; maybeRun becomes a no-op from here (ticksRemaining<=0 below)
   }
   return;
  }
  if (ticksRemaining <= 0) return;
  logTick(level, worldProbe);
  logTick(level, localProbe);
  ticksRemaining--;
  if (ticksRemaining == 0) {
   LOGGER.info("[DIV anchorFrameProbe] COMPLETE - compare the two 'drift' series above, and check "
     + "the absolute position/velocity lines confirm BOTH pairs are actually falling under gravity "
     + "(liveness) before trusting either drift number. The hypothesis (world or local) whose "
     + "drift stayed near zero throughout, with confirmed liveness, is Sable's real convention.");
  }
 }

 /** Registers (or retries registering) both bodies of this pair, then builds the constraint
  * exactly once both are confirmed ready via {@code getRuntimeId()!=-1} - review 53251's
  * correction: {@code isActive()} is set true unconditionally before the native registration
  * even attempts, so it cannot be used as a readiness signal; and nothing retries a failed
  * {@code addBox} on its own, so an explicit remove+re-add is attempted every
  * {@link #RETRY_INTERVAL_TICKS} while not yet ready, rather than passively waiting on an
  * attempt that already failed.
  * @return true once this pair's constraint has been built and contacts suppressed. */
 private static boolean activate(ServerLevel level, Probe p) {
  if (p.constraintBuilt) return true;
  var physics = SubLevelContainer.getContainer(level).physicsSystem();
  maybeRetryRegistration(level, p, true);
  maybeRetryRegistration(level, p, false);
  if (!p.bothBodiesReady()) return false;
  PhysicsConstraintHandle joint = physics.getPipeline().addConstraint(p.boxA, p.boxB, p.config);
  boolean valid = joint != null && joint.isValid();
  LOGGER.info("[DIV anchorFrameProbe] pair=" + p.label + " jointCreated=" + (joint != null) + " jointValid=" + valid);
  if (joint == null || !valid) return false; // retry next tick - do not mark constraintBuilt
  joint.setContactsEnabled(false); // ticket's explicit "disable collisions between constrained chassis pieces"
  p.joint = joint;
  p.constraintBuilt = true;
  return true;
 }

 private static void maybeRetryRegistration(ServerLevel level, Probe p, boolean isA) {
  var physics = SubLevelContainer.getContainer(level).physicsSystem();
  BoxPhysicsObject box = isA ? p.boxA : p.boxB;
  if (box.getRuntimeId() != -1) return; // already ready
  int lastRetry = isA ? p.lastRetryTickA : p.lastRetryTickB;
  if (activationTicksElapsed - lastRetry < RETRY_INTERVAL_TICKS) return; // too soon to retry again
  if (isA) p.lastRetryTickA = activationTicksElapsed; else p.lastRetryTickB = activationTicksElapsed;
  if (box.isActive()) physics.removeObject(box); // only remove if actually currently tracked, so a first-ever attempt below doesn't double-add
  physics.addObject(box);
 }

 /** Force-loads (and keeps loaded) the chunks a probe pair's two boxes sit in, so their physics
  * registration does not depend on this disposable, playerless world happening to have already
  * loaded/generated terrain at a remote test location. */
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

 /** Constructs the two {@code BoxPhysicsObject}s and registers them (first attempt) - does NOT
  * build the constraint here; that happens only once {@link #activate} confirms both bodies are
  * actually ready (review 53251's correction - the first two versions built the constraint
  * speculatively, before the bodies were confirmed registered). */
 private static Probe buildPair(ServerLevel level, String label, Vector3d posA, Vector3d posB, Quaterniond sharedQ,
     Vector3d anchorOffsetInA, Vector3d anchorOffsetInB, GenericConstraintConfiguration config) {
  var physics = SubLevelContainer.getContainer(level).physicsSystem();
  var boxA = new BoxPhysicsObject(new Pose3d(new Vector3d(posA), new Quaterniond(sharedQ), new Vector3d(), new Vector3d(1)), HALF_EXTENTS, MASS);
  var boxB = new BoxPhysicsObject(new Pose3d(new Vector3d(posB), new Quaterniond(sharedQ), new Vector3d(), new Vector3d(1)), HALF_EXTENTS, MASS);
  physics.addObject(boxA);
  physics.addObject(boxB);
  Quaterniond initialRelativeOrientation = new Quaterniond(sharedQ).invert().mul(new Quaterniond(sharedQ));
  return new Probe(label, boxA, boxB, config, new Vector3d(anchorOffsetInA), initialRelativeOrientation);
 }

 private static void logTick(ServerLevel level, Probe p) {
  p.boxA.updatePose();
  p.boxB.updatePose();
  var poseA = p.boxA.getPose();
  var poseB = p.boxB.getPose();
  var physics = SubLevelContainer.getContainer(level).physicsSystem();
  Vector3d velA = physics.getPipeline().getLinearVelocity(p.boxA, new Vector3d());
  Vector3d velB = physics.getPipeline().getLinearVelocity(p.boxB, new Vector3d());
  // B's own anchor point (in B's local frame, offset +ANCHOR_OFFSET_MAGNITUDE along local Y),
  // expressed in A's local frame right now - at zero drift this must stay exactly equal to
  // p.initialRelativeAnchorInA() for all time (a true fixed joint never lets the anchor move
  // relative to either body).
  Vector3d anchorBWorld = new Vector3d(poseB.orientation().transform(new Vector3d(0, ANCHOR_OFFSET_MAGNITUDE, 0))).add(poseB.position());
  Vector3d anchorBInA = new Vector3d(anchorBWorld).sub(poseA.position());
  new Quaterniond(poseA.orientation()).invert().transform(anchorBInA);
  double positionDrift = anchorBInA.distance(p.initialRelativeAnchorInA);
  Quaterniond relativeOrientationNow = new Quaterniond(poseA.orientation()).invert().mul(new Quaterniond(poseB.orientation()));
  double orientationDriftRadians = relativeOrientationNow.invert().mul(new Quaterniond(p.initialRelativeOrientation)).angle();
  LOGGER.info("[DIV anchorFrameProbe] pair=" + p.label
    + " positionDrift=" + positionDrift + "m orientationDrift=" + Math.toDegrees(orientationDriftRadians) + "deg"
    + " posA=" + poseA.position() + " velA=" + velA + " posB=" + poseB.position() + " velB=" + velB);
 }
}
