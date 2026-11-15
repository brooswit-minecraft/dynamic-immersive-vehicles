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
 * Bounded, opt-in, CI/isolated-host-only runtime probe (MINECRAFT-530 review, comment 54033:
 * "missing required real-Sable evidence for the new installed-part frames... the existing
 * historical probe uses raw root-local offsets/frames rather than these new installed-part-
 * composed values") answering the question {@link HitchRotationFrameProbe} deliberately could
 * not: given a hitch/hookup connection authored on an INSTALLED PART - translated, rotated AND
 * scaled relative to its owning vehicle, on BOTH sides at once - does the REAL native Sable
 * engine actually settle the two bodies into the anchor-coincident, correctly-oriented
 * configuration {@code HitchJoint.partConnectionOffset}/{@code partConnectionRotation} (via
 * {@code vehicleRootLocalOrientation}) predict, once those composed values are fed through the
 * EXACT SAME {@code GenericConstraintConfiguration} shape {@code PhysicsBridge.createHitch}
 * itself builds?
 *
 * <p>Method: mirrors {@code HitchRotationFrameProbe}'s own proven lifecycle (chunk-force/wait,
 * bounded activation retry, bounded measurement window, single terminal-failure path, idempotent
 * cleanup) exactly, substituting its raw root-local {@code HITCH_ROT}/{@code HITCH_LOCAL_OFFSET}
 * constants with values run through the ACTUAL new composition pipeline instead: a synthetic
 * "part" on each side (its own {@code localOffset}/{@code scale} and an {@code orientation} that
 * - exactly like the real, pinned {@code APart.update()} source {@code PhysicsBridge}'s doc
 * comment and this ticket's review both verified - is the owning vehicle's own orientation
 * composed with that part's own local rotation, {@code RotationMatrix.multiply}'s
 * {@code this=this*other}), plus a connection authored on that part's own local frame. Calling
 * {@code HitchJoint.vehicleRootLocalOrientation}/{@code partConnectionOffset}/
 * {@code partConnectionRotation} - the exact production call shape
 * {@code PhysicsBridge.connectionOffsetVehicleLocal}/{@code connectionRotationVehicleLocal} use -
 * on these synthetic-but-realistic part values produces the same {@code pos1}/{@code pos2}/
 * {@code local_frame1}/{@code local_frame2} production would compute for a live installed part,
 * without needing a full live {@code EntityVehicleF_Physics}/{@code APart} scaffold (this probe's
 * own bounded scope: the composition math feeding the REAL engine, not IV's part-install
 * lifecycle itself, which no ticket here touches).
 *
 * <p>Both the towing-side part and the towed-side part are translated, rotated AND non-uniformly
 * scaled relative to their own owning vehicle (the ticket's explicit "translated/rotated/scaled
 * part endpoints on either side" ask), with the hitch/hookup connection itself ALSO authored with
 * its own non-identity {@code pos}/{@code rot} on that part - so a bug that drops any one of
 * offset, scale or rotation, on either side, changes the predicted anchor/orientation and would
 * be caught. The towed body starts at IDENTITY orientation (deliberately NOT the constraint-
 * implied one, matching {@code HitchRotationFrameProbe}'s own "must visibly converge" discipline)
 * so the joint has to visibly rotate it into place rather than starting already-satisfied, which
 * would prove nothing. "towing" is again far heavier (10000kg) than "towed" (10kg) for the same
 * reason - so towing acts as a practically-fixed anchor and its own initial orientation stands in
 * for its orientation throughout the measurement window.
 *
 * <p>After settling, two independent checks (either one failing would mean an owner-missed
 * composition bug the pure-geometry unit tests, which never touch Sable, structurally cannot
 * catch): (1) anchor coincidence - the hitch and hookup anchor points, recomputed from each
 * body's CURRENT (not initial) world pose, must converge to the same world point; (2) relative
 * orientation - the settled {@code inverse(Q_towing) * Q_towed} must match the native
 * composition {@code local_frame1 * inverse(local_frame2)} prediction built from the SAME
 * composed-through-the-part values the joint itself was configured with.
 *
 * <p>Opt-in via {@code -Ddiv.experimentalInstalledPartHitchFrameProbe=true} - OFF by default,
 * never runs during normal play. Runs ONCE per server lifetime, for a bounded window, then stops.
 */
public final class InstalledPartHitchFrameProbe {
 private InstalledPartHitchFrameProbe() {}
 private static final boolean ENABLED = Boolean.getBoolean("div.experimentalInstalledPartHitchFrameProbe");
 private static final int PROBE_TICKS = 100;
 private static final int MAX_ACTIVATION_TICKS = 400;
 private static final int RETRY_INTERVAL_TICKS = 40;
 private static final int MAX_CHUNK_WAIT_TICKS = 200;
 private static final double TOWING_MASS = 10000.0, TOWED_MASS = 10.0;
 private static final Vector3d HALF_EXTENTS = new Vector3d(0.3, 0.3, 0.3);
 private static final double POSITION_TOLERANCE = 0.05;

 // --- Towing side: vehicle orientation, and an installed part translated/rotated/scaled
 // relative to it, with a hitch connection authored on that part's own local frame. ---
 private static final RotationMatrix TOWING_VEHICLE_ROT = new RotationMatrix().rotateY(20).rotateX(10);
 private static final RotationMatrix TOWING_PART_LOCAL_ROT = new RotationMatrix().rotateZ(15);
 private static final Vector3d TOWING_PART_LOCAL_OFFSET = new Vector3d(0.5, 0, -0.3);
 private static final Vector3d TOWING_PART_SCALE = new Vector3d(2, 1, 1);
 private static final Vector3d HITCH_CONNECTION_LOCAL_POS = new Vector3d(0, 0, -1);
 private static final RotationMatrix HITCH_CONNECTION_ROT = new RotationMatrix().rotateX(-6);

 // --- Towed side: vehicle starts at IDENTITY (must visibly converge), with its own installed
 // part translated/rotated/scaled relative to it, hookup connection on that part's own frame. ---
 private static final RotationMatrix TOWED_VEHICLE_ROT = new RotationMatrix();
 private static final RotationMatrix TOWED_PART_LOCAL_ROT = new RotationMatrix().rotateY(25);
 private static final Vector3d TOWED_PART_LOCAL_OFFSET = new Vector3d(-0.4, 0, 0.6);
 private static final Vector3d TOWED_PART_SCALE = new Vector3d(1, 1, 1.5);
 private static final Vector3d HOOKUP_CONNECTION_LOCAL_POS = new Vector3d(0, 0, 1);
 private static final RotationMatrix HOOKUP_CONNECTION_ROT = new RotationMatrix().rotateY(12);

 private static boolean chunksRequested = false, boxesCreated = false, measuring = false;
 private static boolean terminal = false, cleanedUp = false;
 private static int chunkWaitTicksElapsed = 0, activationTicksElapsed = 0, ticksRemaining = 0;
 private static int[] chunkCoords;
 private static boolean chunkForcedByProbe = false;
 private static BoxPhysicsObject boxTowing, boxTowed;
 private static GenericConstraintConfiguration config;
 private static PhysicsConstraintHandle joint;
 private static boolean constraintBuilt = false;
 private static int lastRetryTickTowing = 0, lastRetryTickTowed = 0;
 private static Vector3d hitchOffsetVehicleLocal, hookupOffsetVehicleLocal;
 private static Quaterniond local_frame1, local_frame2, hookupRotVehicleLocalRaw;
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
    LOGGER.info("[DIV installedPartHitchFrameProbe] activated after " + activationTicksElapsed + " ticks - beginning " + PROBE_TICKS + "-tick measurement");
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
   finishTerminal(level);
  }
 }

 private static void fail(ServerLevel level, String message, RuntimeException cause) {
  if (cause != null) LOGGER.error("[DIV installedPartHitchFrameProbe] FAILED - " + message, cause);
  else LOGGER.error("[DIV installedPartHitchFrameProbe] FAILED - " + message);
  finishTerminal(level);
 }

 private static void finishTerminal(ServerLevel level) {
  try {
   cleanup(level);
  } finally {
   terminal = true;
  }
 }

 private static void cleanup(ServerLevel level) {
  if (cleanedUp) return;
  cleanedUp = true;
  var physics = SubLevelContainer.getContainer(level).physicsSystem();
  if (joint != null && joint.isValid()) joint.remove();
  if (boxTowing != null && boxTowing.isActive()) physics.removeObject(boxTowing);
  if (boxTowed != null && boxTowed.isActive()) physics.removeObject(boxTowed);
  if (chunkCoords != null && chunkForcedByProbe) level.setChunkForced(chunkCoords[0], chunkCoords[1], false);
  LOGGER.info("[DIV installedPartHitchFrameProbe] cleaned up probe joint/boxes" + (chunkForcedByProbe ? " and released its own forced chunk" : " - chunk was already forced by another owner, left untouched"));
 }

 private static boolean activate(ServerLevel level) {
  if (constraintBuilt) return true;
  var physics = SubLevelContainer.getContainer(level).physicsSystem();
  maybeRetryRegistration(level, boxTowing, true);
  maybeRetryRegistration(level, boxTowed, false);
  if (boxTowing.getRuntimeId() == -1 || boxTowed.getRuntimeId() == -1) return false;
  PhysicsConstraintHandle j = physics.getPipeline().addConstraint(boxTowing, boxTowed, config);
  boolean valid = j != null && j.isValid();
  LOGGER.info("[DIV installedPartHitchFrameProbe] jointCreated=" + (j != null) + " jointValid=" + valid);
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
  int chunkX = Math.floorDiv(6200, 16), chunkZ = Math.floorDiv(6200, 16);
  chunkCoords = new int[]{chunkX, chunkZ};
  chunkForcedByProbe = level.setChunkForced(chunkX, chunkZ, true);
  LOGGER.info("[DIV installedPartHitchFrameProbe] requested chunk (" + chunkX + "," + chunkZ + ") forcedByProbe=" + chunkForcedByProbe);
 }

 private static boolean chunksActuallyLoaded(ServerLevel level) {
  return level.getChunkSource().getChunkNow(chunkCoords[0], chunkCoords[1]) != null;
 }

 private static void createBoxes(ServerLevel level) {
  // Exact production call shape PhysicsBridge.connectionOffsetVehicleLocal/
  // connectionRotationVehicleLocal use: APart.orientation's real relation is
  // entityOn.orientation * localOrientation, which for a top-level part (entityOn == vehicle)
  // is vehicleOrientation * partLocalRot - constructed directly here rather than needing a live
  // APart instance.
  var towingPartFullOrientation = new RotationMatrix().set(TOWING_VEHICLE_ROT).multiply(TOWING_PART_LOCAL_ROT);
  var towingPartVehicleRootLocalOrientation = HitchJoint.vehicleRootLocalOrientation(TOWING_VEHICLE_ROT, towingPartFullOrientation);
  hitchOffsetVehicleLocal = HitchJoint.partConnectionOffset(TOWING_PART_LOCAL_OFFSET, towingPartVehicleRootLocalOrientation,
    TOWING_PART_SCALE, HITCH_CONNECTION_LOCAL_POS, new Vector3d());
  local_frame1 = HitchJoint.partConnectionRotation(towingPartVehicleRootLocalOrientation, HITCH_CONNECTION_ROT);

  var towedPartFullOrientation = new RotationMatrix().set(TOWED_VEHICLE_ROT).multiply(TOWED_PART_LOCAL_ROT);
  var towedPartVehicleRootLocalOrientation = HitchJoint.vehicleRootLocalOrientation(TOWED_VEHICLE_ROT, towedPartFullOrientation);
  hookupOffsetVehicleLocal = HitchJoint.partConnectionOffset(TOWED_PART_LOCAL_OFFSET, towedPartVehicleRootLocalOrientation,
    TOWED_PART_SCALE, HOOKUP_CONNECTION_LOCAL_POS, new Vector3d());
  hookupRotVehicleLocalRaw = HitchJoint.partConnectionRotation(towedPartVehicleRootLocalOrientation, HOOKUP_CONNECTION_ROT);
  local_frame2 = new Quaterniond(hookupRotVehicleLocalRaw).invert();

  var qTowing = HitchJoint.localJointFrame(TOWING_VEHICLE_ROT);
  var qTowedInitial = new Quaterniond(); // deliberately NOT the constraint-implied orientation
  Vector3d towingPos = new Vector3d(6200, 80, 6200);
  Vector3d anchorWorld = new Vector3d(qTowing.transform(new Vector3d(hitchOffsetVehicleLocal))).add(towingPos);
  Vector3d towedPos = new Vector3d(anchorWorld).sub(qTowedInitial.transform(new Vector3d(hookupOffsetVehicleLocal)));

  boxTowing = new BoxPhysicsObject(new Pose3d(towingPos, new Quaterniond(qTowing), new Vector3d(), new Vector3d(1)), HALF_EXTENTS, TOWING_MASS);
  boxTowed = new BoxPhysicsObject(new Pose3d(towedPos, new Quaterniond(qTowedInitial), new Vector3d(), new Vector3d(1)), HALF_EXTENTS, TOWED_MASS);
  var physics = SubLevelContainer.getContainer(level).physicsSystem();
  physics.addObject(boxTowing);
  physics.addObject(boxTowed);

  config = new GenericConstraintConfiguration(hitchOffsetVehicleLocal, hookupOffsetVehicleLocal,
    local_frame1, local_frame2, HitchJoint.lockedAxes(true, false));
  LOGGER.info("[DIV installedPartHitchFrameProbe] boxes created after " + chunkWaitTicksElapsed + " chunk-wait ticks - hitchOffsetVehicleLocal="
    + hitchOffsetVehicleLocal + " hookupOffsetVehicleLocal=" + hookupOffsetVehicleLocal + " anchorWorld=" + anchorWorld
    + " towingPos=" + towingPos + " towedPos=" + towedPos);
 }

 private static void logTick(ServerLevel level) {
  boxTowing.updatePose();
  boxTowed.updatePose();
  var physics = SubLevelContainer.getContainer(level).physicsSystem();
  Vector3d velTowing = physics.getPipeline().getLinearVelocity(boxTowing, new Vector3d());
  Vector3d velTowed = physics.getPipeline().getLinearVelocity(boxTowed, new Vector3d());
  Vector3d angTowed = physics.getPipeline().getAngularVelocity(boxTowed, new Vector3d());
  LOGGER.info("[DIV installedPartHitchFrameProbe] posTowing=" + boxTowing.getPose().position() + " posTowed=" + boxTowed.getPose().position()
    + " velTowing=" + velTowing + " velTowed=" + velTowed + " angVelTowed=" + angTowed
    + " orientationTowed=" + boxTowed.getPose().orientation());
 }

 private static void logConclusion() {
  var qTowingFinal = new Quaterniond(boxTowing.getPose().orientation());
  var qTowedFinal = new Quaterniond(boxTowed.getPose().orientation());

  // Check 1: anchor coincidence, recomputed from each body's CURRENT (settled) world pose.
  Vector3d anchorFromTowing = new Vector3d(qTowingFinal.transform(new Vector3d(hitchOffsetVehicleLocal))).add(boxTowing.getPose().position());
  Vector3d anchorFromTowed = new Vector3d(qTowedFinal.transform(new Vector3d(hookupOffsetVehicleLocal))).add(boxTowed.getPose().position());
  double anchorGap = new Vector3d(anchorFromTowing).sub(anchorFromTowed).length();

  // Check 2: relative orientation, against the native composition built from the SAME
  // composed-through-the-part values the joint itself was configured with (hookupRotVehicleLocalRaw
  // is the un-inverted form - see PhysicsBridge.createHitch's own doc comment for the native
  // Q_towed = Q_towing * R_hitch * R_hookup relation, now with R_hitch/R_hookup being the
  // part-composed values rather than bare connection.rot).
  var relativeActual = new Quaterniond(qTowingFinal).invert().mul(qTowedFinal);
  var relativeNative = new Quaterniond(local_frame1).mul(hookupRotVehicleLocalRaw);
  double angleToNative = Math.toDegrees(new Quaterniond(relativeActual).invert().mul(relativeNative).angle());

  LOGGER.info("[DIV installedPartHitchFrameProbe] COMPLETE - anchorFromTowing=" + anchorFromTowing + " anchorFromTowed=" + anchorFromTowed
    + " anchorGap=" + anchorGap + " (tolerance=" + POSITION_TOLERANCE + ", PASS=" + (anchorGap < POSITION_TOLERANCE) + ")"
    + " relativeActual=" + relativeActual + " angleToNativePrediction=" + angleToNative + "deg (PASS=" + (angleToNative < 1.0) + ")"
    + " - both PASS means the real Sable engine settled translated/rotated/scaled installed-part"
    + " hitch/hookup endpoints, on both sides at once, into exactly the anchor/orientation"
    + " HitchJoint's new part-composition helpers predicted.");
 }
}
