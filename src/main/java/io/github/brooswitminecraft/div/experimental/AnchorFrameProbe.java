package io.github.brooswitminecraft.div.experimental;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintConfiguration;
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
 * Bounded, opt-in, CI/isolated-host-only runtime probe (ticket comment 53244) answering the
 * question comment 53243 raised and could NOT settle from static reading: does Sable's real
 * (native, not decompilable from the public API jar) constraint solver treat
 * {@code GenericConstraintConfiguration}'s {@code pos1}/{@code pos2} as WORLD-space points or as
 * BODY-LOCAL offsets? DIV's own existing, shipped hitch code ({@code PhysicsBridge.createHitch})
 * assumes world-space; review's own direct testing claimed body-local "despite docs" - a direct
 * conflict this class exists to resolve empirically, once, away from any real vehicle or Zippy.
 *
 * <p>Method: build TWO independent, non-interacting pairs of throwaway boxes, far from world
 * origin (x=5000) and each with a distinct, non-axis-aligned rotation (so world-space and body-
 * local offsets are numerically different, not accidentally coincident) and bodies arranged so
 * the two interpretations predict DIFFERENT required pos1/pos2 values for the exact same intended
 * physical configuration. Pair "world" is given the WORLD-space hypothesis values; pair "local" is
 * given the BODY-LOCAL hypothesis values. Both pairs start at an IDENTICAL, internally-consistent
 * initial configuration (the shared anchor point G sits exactly at each body's own intended
 * offset by construction, verified in this class's own doc below) and are jointed with all 6 axes
 * locked (a true fixed joint, via the same {@code HitchJoint.lockedAxes(true,false)} this
 * codebase's own shipped hitch code already uses). Whichever pair's real engine behavior matches
 * its own hypothesis will show near-zero RELATIVE drift between the two bodies over the probe
 * window (the joint successfully holds the already-correct configuration); the other pair's
 * hypothesis is wrong for its own anchors, so the solver visibly drags the bodies toward a
 * different relative configuration to satisfy the (wrongly-addressed) constraint - a real,
 * measurable, logged divergence, not a subtle numerical difference.
 *
 * <p>Gravity acts on both pairs identically and is NOT disabled - irrelevant to this measurement,
 * since drift is computed from each pair's own RELATIVE pose (bodyB's anchor point as seen from
 * bodyA), not absolute world position; a correctly-jointed pair free-falls together with zero
 * relative drift regardless of gravity.
 *
 * <p>Opt-in via {@code -Ddiv.experimentalAnchorFrameProbe=true} - OFF by default, never runs
 * during normal play, intended only for a disposable/CI dedicated-server run per the ticket's own
 * "keep heavy builds/runtime validation on CI... preserve Zippy unchanged" instruction. Runs ONCE
 * per server lifetime (first tick after a ServerLevel exists), for a bounded window, then stops
 * touching anything further.
 */
public final class AnchorFrameProbe {
 private AnchorFrameProbe() {}
 private static final boolean ENABLED = Boolean.getBoolean("div.experimentalAnchorFrameProbe");
 private static final int PROBE_TICKS = 60; // 3 real-time seconds at 20tps - long enough for the solver to visibly settle or diverge, short enough to stay bounded
 private static final double MASS = 10.0;
 private static final Vector3d HALF_EXTENTS = new Vector3d(0.5, 0.5, 0.5);
 private static boolean started = false;
 private static int ticksRemaining = 0;
 private static Probe worldProbe, localProbe;
 private static final Logger LOGGER = LogUtils.getLogger();

 private record Probe(String label, BoxPhysicsObject boxA, BoxPhysicsObject boxB, RigidBodyHandle handleA, RigidBodyHandle handleB,
   Vector3d initialRelativeAnchorInA, Quaterniond initialRelativeOrientation) {}

 /** Call once per server tick after a ServerLevel is confirmed to exist (see
  * DynamicImmersiveVehicles' own tick hook) - cheap no-op unless the opt-in flag is set and the
  * probe hasn't already run to completion this server lifetime. */
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
  logTick(worldProbe);
  logTick(localProbe);
  ticksRemaining--;
  if (ticksRemaining == 0) {
   LOGGER.info("[DIV anchorFrameProbe] COMPLETE - compare the two 'drift' series above. The "
     + "hypothesis (world or local) whose drift stayed near zero throughout is Sable's real "
     + "pos1/pos2 convention for GenericConstraintConfiguration.");
  }
 }

 private static void start(ServerLevel level) {
  var physics = SubLevelContainer.getContainer(level).physicsSystem();
  // Base reference position/orientation for body A of each pair - far from origin, per the
  // ticket's explicit "well away from the world origin" requirement, so a world-vs-local
  // misinterpretation produces a LARGE, unmistakable numeric error rather than one that could
  // be masked by coincidental smallness near (0,0,0).
  Vector3d posA = new Vector3d(5000, 80, 5000);
  Quaterniond qA = new Quaterniond().rotateY(Math.toRadians(30)).rotateX(Math.toRadians(15));
  Quaterniond qB = new Quaterniond().rotateY(Math.toRadians(-20)).rotateZ(Math.toRadians(25));
  // The TRUE intended physical relationship (independent of which hypothesis is fed to Sable):
  // body A's own anchor offset (in A's own local frame) and body B's own anchor offset (in B's
  // own local frame) must coincide at one shared world point G.
  Vector3d anchorOffsetInA = new Vector3d(0, -0.5, 0), anchorOffsetInB = new Vector3d(0, 0.5, 0);
  Vector3d worldG = new Vector3d(qA.transform(new Vector3d(anchorOffsetInA))).add(posA);
  Vector3d posB = new Vector3d(worldG).sub(qB.transform(new Vector3d(anchorOffsetInB)));
  worldProbe = buildPair(level, "world", posA, qA, posB, qB,
    new GenericConstraintConfiguration(worldG, worldG, qA, qB, HitchJoint.lockedAxes(true, false)));
  // Second pair, offset far away in X so it cannot physically interact with the first, otherwise
  // identical physical configuration (same posA/qA/posB/qB/anchor offsets) but given the BODY-
  // LOCAL hypothesis instead.
  Vector3d posA2 = new Vector3d(posA).add(500, 0, 0);
  Vector3d worldG2 = new Vector3d(qA.transform(new Vector3d(anchorOffsetInA))).add(posA2);
  Vector3d posB2 = new Vector3d(worldG2).sub(qB.transform(new Vector3d(anchorOffsetInB)));
  localProbe = buildPair(level, "local", posA2, qA, posB2, qB,
    new GenericConstraintConfiguration(anchorOffsetInA, anchorOffsetInB, qA, qB, HitchJoint.lockedAxes(true, false)));
  LOGGER.info("[DIV anchorFrameProbe] started - worldG=" + worldG + " worldG2=" + worldG2 + " probeTicks=" + PROBE_TICKS);
 }

 private static Probe buildPair(ServerLevel level, String label,
     Vector3d posA, Quaterniond qA, Vector3d posB, Quaterniond qB,
     GenericConstraintConfiguration config) {
  var physics = SubLevelContainer.getContainer(level).physicsSystem();
  var boxA = new BoxPhysicsObject(new Pose3d(new Vector3d(posA), new Quaterniond(qA), new Vector3d(), new Vector3d(1)), HALF_EXTENTS, MASS);
  var boxB = new BoxPhysicsObject(new Pose3d(new Vector3d(posB), new Quaterniond(qB), new Vector3d(), new Vector3d(1)), HALF_EXTENTS, MASS);
  physics.addObject(boxA);
  physics.addObject(boxB);
  var handleA = RigidBodyHandle.of(level, boxA);
  var handleB = RigidBodyHandle.of(level, boxB);
  physics.getPipeline().addConstraint(boxA, boxB, config);
  // Record the TRUE initial relative configuration (independent of either hypothesis) so later
  // ticks can measure drift away from it directly, regardless of which hypothesis this pair was
  // actually given.
  Vector3d initialRelativeAnchorInA = new Vector3d(0, -0.5, 0); // == anchorOffsetInA by construction
  Quaterniond initialRelativeOrientation = new Quaterniond(qA).invert().mul(qB);
  return new Probe(label, boxA, boxB, handleA, handleB, initialRelativeAnchorInA, initialRelativeOrientation);
 }

 private static void logTick(Probe p) {
  p.boxA().updatePose();
  p.boxB().updatePose();
  var poseA = p.boxA().getPose();
  var poseB = p.boxB().getPose();
  // Where does B's own anchor point (in B's local frame) actually sit, expressed in A's local
  // frame right now? At zero drift this must stay exactly equal to p.initialRelativeAnchorInA()
  // for all time (a true fixed joint never lets the anchor move relative to either body).
  Vector3d anchorBWorld = new Vector3d(poseB.orientation().transform(new Vector3d(0, 0.5, 0))).add(poseB.position());
  Vector3d anchorBInA = new Vector3d(anchorBWorld).sub(poseA.position());
  new Quaterniond(poseA.orientation()).invert().transform(anchorBInA);
  double positionDrift = anchorBInA.distance(p.initialRelativeAnchorInA());
  Quaterniond relativeOrientationNow = new Quaterniond(poseA.orientation()).invert().mul(new Quaterniond(poseB.orientation()));
  double orientationDriftRadians = relativeOrientationNow.invert().mul(new Quaterniond(p.initialRelativeOrientation())).angle();
  LOGGER.info("[DIV anchorFrameProbe] pair=" + p.label() + " positionDrift=" + positionDrift + "m orientationDrift=" + Math.toDegrees(orientationDriftRadians) + "deg");
 }
}
