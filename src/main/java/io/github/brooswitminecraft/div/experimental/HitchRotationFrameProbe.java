package io.github.brooswitminecraft.div.experimental;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.api.physics.object.box.BoxPhysicsObject;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import io.github.brooswitminecraft.div.HitchJoint;
import com.mojang.logging.LogUtils;
import minecrafttransportsimulator.baseclasses.RotationMatrix;
import net.minecraft.server.level.ServerLevel;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;

/**
 * Bounded, opt-in, CI/isolated-host-only runtime probe (MINECRAFT-525, review 53817/53832)
 * answering the question {@code AnchorFrameProbe} deliberately left open: given NON-IDENTITY
 * {@code local_frame1}/{@code local_frame2} on BOTH sides of a fully-locked ("mounted") Sable
 * constraint, what relative orientation does the REAL native engine actually enforce between
 * the two bodies - {@code Q_towed = Q_towing * local_frame1 * inverse(local_frame2)} (review
 * 53832's own stated relation for this constraint) or something else? {@code AnchorFrameProbe}
 * itself only ever used ONE shared orientation for every body in each pair, deliberately
 * isolating itself to the pos1/pos2 (world-vs-local) question alone (its own v4 history note) -
 * it says nothing about how two DIFFERENT local frames on two DIFFERENTLY-oriented bodies
 * actually compose, which is exactly the question review's sign finding turns on.
 *
 * <p>Method: ONE pair, not two - unlike {@code AnchorFrameProbe}'s dual-hypothesis setup, this
 * probe does not need a side-by-side comparison, because it can read back the actual settled
 * relative orientation directly and compare it post-hoc against BOTH candidate predictions
 * (native: {@code R_hitch * R_hookup}; wrong-sign: {@code R_hitch * inverse(R_hookup)}) - exactly
 * one of them will match. "towing" is deliberately far heavier (10000kg) than "towed" (10kg) so
 * the small reaction torque from towed settling into the constraint cannot meaningfully perturb
 * towing's own orientation - towing acts as a practically-fixed anchor, letting the comparison
 * use towing's INITIAL orientation as a stand-in for its (still free, not kinematically fixed)
 * actual orientation throughout. Bodies start TRANSLATED (towing and towed at different
 * positions, non-zero local anchor offsets on each side - the "translated" case the ticket and
 * review both explicitly ask for) and at DIFFERENT, non-identity initial orientations, so the
 * joint must visibly rotate "towed" into the constraint-implied orientation rather than starting
 * already-satisfied (which would prove nothing about which composition convention is real).
 * {@code local_frame1}/{@code local_frame2} are built via the EXACT PRODUCTION call shape
 * {@code PhysicsBridge.createHitch} itself uses - {@code HitchJoint.localJointFrame(hitchRot)}
 * and {@code HitchJoint.localJointFrame(hookupRot).invert()} - so this probe tests the actual
 * fix, not a reimplementation of it.
 *
 * <p>Opt-in via {@code -Ddiv.experimentalHitchRotationFrameProbe=true} - OFF by default, never
 * runs during normal play. Runs ONCE per server lifetime, for a bounded window, then stops.
 * Follows the exact chunk-force-load / genuine-load-confirmation / bounded-registration-retry
 * lifecycle {@code AnchorFrameProbe} already established and proved necessary.
 */
public final class HitchRotationFrameProbe {
 private HitchRotationFrameProbe() {}
 private static final boolean ENABLED = Boolean.getBoolean("div.experimentalHitchRotationFrameProbe");
 private static final int PROBE_TICKS = 100; // 5 real-time seconds at 20tps - long enough for a fully-locked joint to visibly settle
 private static final int MAX_ACTIVATION_TICKS = 400;
 private static final int RETRY_INTERVAL_TICKS = 40;
 private static final int MAX_CHUNK_WAIT_TICKS = 200;
 private static final double TOWING_MASS = 10000.0, TOWED_MASS = 10.0;
 private static final Vector3d HALF_EXTENTS = new Vector3d(0.3, 0.3, 0.3);
 // Review's own worked example generalized to BOTH endpoints and BOTH translation and relative
 // rotation at once, per the ticket's explicit "translated/rotated tow and trailer bodies" ask.
 private static final RotationMatrix TOWING_ROT = new RotationMatrix().rotateY(20).rotateX(10);
 private static final RotationMatrix HITCH_ROT = new RotationMatrix().rotateZ(-8);
 private static final RotationMatrix HOOKUP_ROT = new RotationMatrix().rotateY(30).rotateX(5);
 private static final Vector3d HITCH_LOCAL_OFFSET = new Vector3d(0, 0, -1); // behind towing's own origin
 private static final Vector3d HOOKUP_LOCAL_OFFSET = new Vector3d(0, 0, 1); // ahead of towed's own origin
 private static boolean chunksRequested = false, boxesCreated = false, measuring = false;
 // Review (PR comment 6871906290): a chunk-wait/create failure previously only set
 // measuring=true/ticksRemaining=0, which does NOT stop the next tick from re-entering
 // `if (!boxesCreated)` and retrying creation indefinitely - boxesCreated itself was never set.
 // `terminal` is the single, explicit "this probe is permanently done - success OR failure, never
 // retry" guard, checked FIRST on every tick; `cleanedUp` makes releasing this probe's own
 // resources (joint/boxes/forced chunk - nothing else) idempotent regardless of which exit path
 // reaches it (normal completion, or any of the three failure/timeout paths).
 private static boolean terminal = false, cleanedUp = false;
 private static int chunkWaitTicksElapsed = 0, activationTicksElapsed = 0, ticksRemaining = 0;
 private static int[] chunkCoords;
 private static BoxPhysicsObject boxTowing, boxTowed;
 private static GenericConstraintConfiguration config;
 private static PhysicsConstraintHandle joint;
 private static boolean constraintBuilt = false;
 private static int lastRetryTickTowing = 0, lastRetryTickTowed = 0;
 private static final Logger LOGGER = LogUtils.getLogger();

 public static void maybeRun(ServerLevel level) {
  if (!ENABLED || terminal) return;
  if (!chunksRequested) {
   chunksRequested = true;
   try {
    requestChunks(level);
   } catch (RuntimeException ex) {
    fail(level, "failed to request chunks", ex);
   }
   return;
  }
  if (!boxesCreated) {
   chunkWaitTicksElapsed++;
   if (!chunksActuallyLoaded(level)) {
    if (chunkWaitTicksElapsed > MAX_CHUNK_WAIT_TICKS) {
     fail(level, "chunk never finished loading within " + MAX_CHUNK_WAIT_TICKS + " ticks", null);
    }
    return;
   }
   try {
    createBoxes(level);
    boxesCreated = true;
   } catch (RuntimeException ex) {
    fail(level, "failed to create boxes", ex);
   }
   return;
  }
  if (!measuring) {
   activationTicksElapsed++;
   if (activate(level)) {
    measuring = true;
    ticksRemaining = PROBE_TICKS;
    LOGGER.info("[DIV hitchRotationFrameProbe] activated after " + activationTicksElapsed + " ticks - beginning " + PROBE_TICKS + "-tick measurement");
   } else if (activationTicksElapsed > MAX_ACTIVATION_TICKS) {
    fail(level, "never became ready within " + MAX_ACTIVATION_TICKS + " ticks", null);
   }
   return;
  }
  if (ticksRemaining <= 0) return;
  logTick(level);
  ticksRemaining--;
  if (ticksRemaining == 0) {
   logConclusion();
   cleanup(level);
   terminal = true;
  }
 }

 /** Single terminal-failure path (review's explicit ask): logs once, then permanently stops -
  * {@code terminal=true} makes every subsequent {@code maybeRun} call an immediate no-op, so a
  * chunk/create failure can never re-enter {@code !boxesCreated} and retry. Always cleans up
  * whatever this probe itself already created before marking terminal. */
 private static void fail(ServerLevel level, String message, RuntimeException cause) {
  if (cause != null) LOGGER.error("[DIV hitchRotationFrameProbe] FAILED - " + message, cause);
  else LOGGER.error("[DIV hitchRotationFrameProbe] FAILED - " + message);
  cleanup(level);
  terminal = true;
 }

 /** Idempotent (guarded by {@code cleanedUp}) - removes ONLY this probe's own joint/boxes and
  * its own forced chunk (review's explicit "remove only this probe's joint/boxes and its own
  * forced chunk ticket"), never anything else on the level. Safe to call from any exit path,
  * including ones reached before the joint or boxes (or even the chunk request) ever existed. */
 private static void cleanup(ServerLevel level) {
  if (cleanedUp) return;
  cleanedUp = true;
  var physics = SubLevelContainer.getContainer(level).physicsSystem();
  if (joint != null && joint.isValid()) joint.remove();
  if (boxTowing != null && boxTowing.isActive()) physics.removeObject(boxTowing);
  if (boxTowed != null && boxTowed.isActive()) physics.removeObject(boxTowed);
  if (chunkCoords != null) level.setChunkForced(chunkCoords[0], chunkCoords[1], false);
  LOGGER.info("[DIV hitchRotationFrameProbe] cleaned up probe joint/boxes and released its own forced chunk");
 }

 private static boolean activate(ServerLevel level) {
  if (constraintBuilt) return true;
  var physics = SubLevelContainer.getContainer(level).physicsSystem();
  maybeRetryRegistration(level, boxTowing, true);
  maybeRetryRegistration(level, boxTowed, false);
  if (boxTowing.getRuntimeId() == -1 || boxTowed.getRuntimeId() == -1) return false;
  PhysicsConstraintHandle j = physics.getPipeline().addConstraint(boxTowing, boxTowed, config);
  boolean valid = j != null && j.isValid();
  LOGGER.info("[DIV hitchRotationFrameProbe] jointCreated=" + (j != null) + " jointValid=" + valid);
  if (j == null || !valid) return false;
  j.setContactsEnabled(false);
  joint = j;
  constraintBuilt = true;
  return true;
 }

 private static void maybeRetryRegistration(ServerLevel level, BoxPhysicsObject box, boolean isTowing) {
  var physics = SubLevelContainer.getContainer(level).physicsSystem();
  if (box.getRuntimeId() != -1) return;
  int lastRetry = isTowing ? lastRetryTickTowing : lastRetryTickTowed;
  if (activationTicksElapsed - lastRetry < RETRY_INTERVAL_TICKS) return;
  if (isTowing) lastRetryTickTowing = activationTicksElapsed; else lastRetryTickTowed = activationTicksElapsed;
  if (box.isActive()) physics.removeObject(box);
  physics.addObject(box);
 }

 private static void requestChunks(ServerLevel level) {
  int chunkX = Math.floorDiv(6000, 16), chunkZ = Math.floorDiv(6000, 16);
  chunkCoords = new int[]{chunkX, chunkZ};
  level.setChunkForced(chunkX, chunkZ, true);
  LOGGER.info("[DIV hitchRotationFrameProbe] requested chunk (" + chunkX + "," + chunkZ + ")");
 }

 private static boolean chunksActuallyLoaded(ServerLevel level) {
  return level.getChunkSource().getChunkNow(chunkCoords[0], chunkCoords[1]) != null;
 }

 private static void createBoxes(ServerLevel level) {
  var qTowing = HitchJoint.localJointFrame(TOWING_ROT);
  var qTowedInitial = new Quaterniond(); // deliberately NOT the constraint-implied orientation - must visibly converge
  Vector3d towingPos = new Vector3d(6000, 80, 6000);
  // Anchor point G in world space from towing's own side, so the pair starts geometrically
  // consistent (same "coincide at creation" discipline AnchorFrameProbe itself follows) - this
  // probe is about the ROTATION convention, not re-litigating the already-settled position one.
  Vector3d anchorWorld = new Vector3d(qTowing.transform(new Vector3d(HITCH_LOCAL_OFFSET))).add(towingPos);
  Vector3d towedPos = new Vector3d(anchorWorld).sub(qTowedInitial.transform(new Vector3d(HOOKUP_LOCAL_OFFSET)));

  boxTowing = new BoxPhysicsObject(new Pose3d(towingPos, new Quaterniond(qTowing), new Vector3d(), new Vector3d(1)), HALF_EXTENTS, TOWING_MASS);
  boxTowed = new BoxPhysicsObject(new Pose3d(towedPos, new Quaterniond(qTowedInitial), new Vector3d(), new Vector3d(1)), HALF_EXTENTS, TOWED_MASS);
  var physics = SubLevelContainer.getContainer(level).physicsSystem();
  physics.addObject(boxTowing);
  physics.addObject(boxTowed);

  // The exact production call shape PhysicsBridge.createHitch itself uses.
  config = new GenericConstraintConfiguration(HITCH_LOCAL_OFFSET, HOOKUP_LOCAL_OFFSET,
    HitchJoint.localJointFrame(HITCH_ROT), HitchJoint.localJointFrame(HOOKUP_ROT).invert(),
    HitchJoint.lockedAxes(true, false));
  LOGGER.info("[DIV hitchRotationFrameProbe] boxes created after " + chunkWaitTicksElapsed + " chunk-wait ticks - anchorWorld=" + anchorWorld
    + " towingPos=" + towingPos + " towedPos=" + towedPos);
 }

 private static void logTick(ServerLevel level) {
  boxTowing.updatePose();
  boxTowed.updatePose();
  var physics = SubLevelContainer.getContainer(level).physicsSystem();
  Vector3d velTowing = physics.getPipeline().getLinearVelocity(boxTowing, new Vector3d());
  Vector3d velTowed = physics.getPipeline().getLinearVelocity(boxTowed, new Vector3d());
  Vector3d angTowed = physics.getPipeline().getAngularVelocity(boxTowed, new Vector3d());
  LOGGER.info("[DIV hitchRotationFrameProbe] posTowing=" + boxTowing.getPose().position() + " posTowed=" + boxTowed.getPose().position()
    + " velTowing=" + velTowing + " velTowed=" + velTowed + " angVelTowed=" + angTowed
    + " orientationTowed=" + boxTowed.getPose().orientation());
 }

 private static void logConclusion() {
  var qTowingFinal = new Quaterniond(boxTowing.getPose().orientation());
  var qTowedFinal = new Quaterniond(boxTowed.getPose().orientation());
  // Actual relative orientation: inverse(Q_towing) * Q_towed.
  var relativeActual = new Quaterniond(qTowingFinal).invert().mul(qTowedFinal);
  var rHitch = HitchJoint.localJointFrame(HITCH_ROT);
  var rHookup = HitchJoint.localJointFrame(HOOKUP_ROT);
  var relativeNative = new Quaterniond(rHitch).mul(rHookup); // R_hitch * R_hookup
  var relativeWrongSign = new Quaterniond(rHitch).mul(new Quaterniond(rHookup).invert()); // R_hitch * inverse(R_hookup)
  double angleToNative = Math.toDegrees(new Quaterniond(relativeActual).invert().mul(relativeNative).angle());
  double angleToWrongSign = Math.toDegrees(new Quaterniond(relativeActual).invert().mul(relativeWrongSign).angle());
  LOGGER.info("[DIV hitchRotationFrameProbe] COMPLETE - relativeActual=" + relativeActual
    + " angleToNativePrediction(R_hitch*R_hookup)=" + angleToNative + "deg"
    + " angleToWrongSignPrediction(R_hitch*inverse(R_hookup))=" + angleToWrongSign + "deg"
    + " - whichever angle is near zero is Sable's real convention for this fully-locked joint; "
    + "PhysicsBridge.createHitch's own local_frame2=inverse(R_hookup) fix is correct only if "
    + "angleToNativePrediction is the one near zero.");
 }
}
