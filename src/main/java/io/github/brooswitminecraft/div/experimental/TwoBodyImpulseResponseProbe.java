package io.github.brooswitminecraft.div.experimental;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import com.mojang.logging.LogUtils;
import io.github.brooswitminecraft.div.TwoBodyLifecycle;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;

/**
 * Bounded, opt-in, CI/isolated-host-only runtime probe answering review comment 53280's explicit
 * correction: {@code TwoBodyChassisGroundForceCompositionTest} (committed in 1bd407b) validates
 * only point KINEMATICS of the owner-corrected lever arm - it does NOT validate the actual
 * CONSTRAINED RESPONSE of the real two-body-plus-fixed-joint system, which is a genuinely
 * different physical question: {@code PhysicsBridge.Body.stepGround}'s friction solver sizes its
 * impulses using the OWNER HALF'S OWN mass/inertia alone (as if that half were an isolated free
 * body - see {@code owner.ownerMass()}/{@code owner.ownerInertiaBody()} at every Phase 1-3 call
 * site), but the real joint RIGIDLY COUPLES both halves together, so the half actually receiving
 * the impulse will not respond as an isolated body at all - its real velocity change is governed
 * by the COMBINED assembly's mass/inertia, with the other half along for the ride via the joint.
 *
 * <p>v2 (this version, per review 53282's correction of the first push, 21638a5): four real
 * validity defects fixed, none of them merely cosmetic:
 * <ul>
 *  <li>Scene was NOT verified terrain-free - {@code (5000,80,5000)} is the exact same location/Y
 *  {@code AnchorFrameProbe} uses, which review flagged as possibly terrain-contaminated at that Y.
 *  Fixed: moved to Y=250 (well above any generated overworld terrain) AND added
 *  {@link #isClearOfTerrain} - an explicit block-by-block scan of the whole fall range before
 *  trusting the scene, failing the probe outright rather than silently assuming clearance.</li>
 *  <li>{@code logTick} logged velocities only, no poses or joint drift, despite the ticket
 *  explicitly asking for both. Fixed: {@link #logTick} now logs both halves' full pose and the
 *  joint drift (world-space distance between the shared anchor point G as computed independently
 *  from each half's own current pose/orientation via {@code TwoBodyChassis.jointAnchorLocalIn*}) -
 *  the same "anchor point must coincide from both sides" invariant {@code AnchorFrameProbe}
 *  itself checks, here as live evidence the joint is actually holding during measurement, not
 *  assumed.</li>
 *  <li>The first post-impulse sample was mislabeled tick+0: {@code activationTicksElapsed} is
 *  frozen the instant the impulse is applied (it only increments in the pre-impulse activation
 *  branch), so the old formula always evaluated to {@code MEASURE_TICKS-ticksRemaining}, which is
 *  0 on the very first logged tick even though one full physics step has already elapsed since
 *  the impulse. Fixed: each {@code Scenario} now keeps its own explicit
 *  {@code ticksSinceImpulse} counter, incremented at the TOP of every {@link #logTick} call for
 *  that scenario, so the first logged sample is correctly tick+1 and gravity compensation
 *  ({@code gravity*DT*ticksSinceImpulse}) is no longer off by one tick's worth of gravity.</li>
 *  <li>The COMBINED prediction's translation of the one shared {@code deltaAngular} into each
 *  half's own predicted {@code deltaV} used the INITIAL (construction-time, identity-orientation)
 *  local center offsets as a fixed vector for every logged tick - valid only as an instantaneous,
 *  first-order approximation immediately after the impulse, not several ticks later once the
 *  assembly's own (now-constant) angular velocity has visibly rotated it, as review correctly
 *  pointed out. Fixed, via review's own offered simplification rather than continuously
 *  re-deriving a rotating offset every tick (more bug-prone, and unnecessary for the actual
 *  question asked): the ISOLATED/COMBINED prediction comparison is now computed and logged ONLY
 *  at {@code ticksSinceImpulse==1} (one 0.05s step after the impulse - rotation accumulated by
 *  then is small, and this is the tick that actually distinguishes the two hypotheses, since by
 *  construction both predict the SAME COM-level linear response but diverge immediately in how
 *  that response is distributed/rotated). Every other logged tick still reports MEASURED
 *  velocities/angular velocities, pose, and joint drift - direct settling/liveness evidence, not
 *  a comparison that would silently rely on a now-stale static-offset approximation.</li>
 * </ul>
 *
 * <p>Method, run independently at both of the ticket's requested mass fractions (f=0.5 and
 * f=0.4), each as its own isolated, non-interacting scenario (far apart in X, same pattern as
 * {@code AnchorFrameProbe}'s "world"/"local" pairs): build the real two-half chassis via
 * {@code TwoBodyLifecycle.create} (the exact same production construction path
 * {@code PhysicsBridge.Body}'s constructor uses when {@code EXPERIMENTAL_TWO_BODY_CHASSIS} is
 * on), at IDENTITY orientation (deliberately isolating this probe to the mass/inertia-coupling
 * question alone, per the same "one question per probe" discipline {@code AnchorFrameProbe} v4
 * established - the body-local anchor/orientation convention is already settled by that probe and
 * the real native source, not re-litigated here). Once both halves are confirmed live
 * ({@code getRuntimeId()!=-1} on both, same readiness signal {@code AnchorFrameProbe} proved
 * reliable), apply ONE known, deliberately modest (review's "restrict impulse small") impulse
 * directly to the LOWER half (the realistic case per {@code TwoBodyChassis.isUpperHalf}'s own doc
 * - every wheel on a typical ground vehicle sits below the chassis center) at a representative
 * wheel-anchor point, then let Sable's own engine step the next {@link #MEASURE_TICKS} real
 * server ticks naturally (no manual stepping - Sable already ticks every server tick on its own).
 *
 * <p>Opt-in via {@code -Ddiv.experimentalTwoBodyImpulseProbe=true} - OFF by default, never runs
 * during normal play, never touches any real vehicle, Zippy, or world content. Runs ONCE per
 * server lifetime, for a bounded window, then stops.
 */
public final class TwoBodyImpulseResponseProbe {
 private TwoBodyImpulseResponseProbe() {}
 private static final boolean ENABLED = Boolean.getBoolean("div.experimentalTwoBodyImpulseProbe");
 private static final int MAX_CHUNK_WAIT_TICKS = 200; // 10s - genuine chunk load/generation at a never-visited location
 private static final int MAX_ACTIVATION_TICKS = 100; // 5s - both halves' own addBox registration, same production construction path as Body's own constructor
 private static final int MEASURE_TICKS = 20; // 1s at 20tps - long enough for the joint to visibly settle a coupled response, short enough to stay bounded
 private static final double TOTAL_MASS_KG = 350.0;
 private static final double HALF_EXTENT_A = 0.5, HALF_HEIGHT_B = 1.0, HALF_EXTENT_C = 0.5; // original (unsplit) chassis half-extents
 // A representative wheel anchor relative to G - negative Y (isUpperHalf's own doc: a typical
 // ground vehicle's wheels sit below the chassis center, so this is the realistic, LOWER-owned
 // case, not an arbitrary choice).
 private static final Vector3d WHEEL_ANCHOR_FROM_G = new Vector3d(0.35, -0.65, 0.9);
 // Review 53282's "restrict impulse small": kept modest enough that the rotation accumulated by
 // the single tick the ISOLATED/COMBINED comparison actually uses stays small, without being
 // zero/degenerate - still clearly nonzero in every axis (review's own "nonzero movement" ask).
 private static final Vector3d IMPULSE = new Vector3d(20, 300, 50); // N*s
 // Review 53282's "verified clearance above terrain": Y=250 is well above any generated
 // overworld terrain (build height 320, typical surface well under 200) AND is independently
 // confirmed air (not merely assumed) by isClearOfTerrain before any body is created there.
 private static final double SCENE_Y = 250;
 private static final int HORIZONTAL_CLEARANCE_RADIUS = 6; // blocks either side of X/Z
 private static final int DOWN_CLEARANCE = 20, UP_CLEARANCE = 5; // blocks below/above SCENE_Y - covers the whole MEASURE_TICKS free-fall range with margin

 private static boolean chunksRequested = false, bodiesCreated = false;
 private static int chunkWaitTicksElapsed = 0, activationTicksElapsed = 0, ticksRemaining = 0;
 private static boolean impulseApplied = false, measuring = false;
 private static int[] chunkCoordsToAwait; // [x1,z1,x2,z2] for the two scenarios' locations
 private static Scenario halfScenario, quarterScenario;
 private static final Logger LOGGER = LogUtils.getLogger();

 /** One mass-fraction scenario - everything needed to build it, predict its response, and later
  * compare against the measured one. */
 private static final class Scenario {
  final String label;
  final TwoBodyChassis.Config config;
  final Vector3d centerPosition;
  TwoBodyLifecycle.Bodies bodies;
  Vector3d lowerVelBefore, upperVelBefore, lowerAngularBefore, upperAngularBefore;
  int ticksSinceImpulse = 0;
  Scenario(String label, TwoBodyChassis.Config config, Vector3d centerPosition) {
   this.label = label; this.config = config; this.centerPosition = centerPosition;
  }
  boolean bothHalvesReady() { return bodies != null && bodies.lowerBox().getRuntimeId() != -1 && bodies.upperBox().getRuntimeId() != -1; }
 }

 /** Call once per server tick after a ServerLevel is confirmed to exist. Cheap no-op unless the
  * opt-in flag is set and the probe hasn't already run to completion this server lifetime. */
 public static void maybeRun(ServerLevel level) {
  if (!ENABLED) return;
  if (!chunksRequested) {
   chunksRequested = true;
   try {
    requestChunks(level);
   } catch (RuntimeException ex) {
    LOGGER.error("[DIV twoBodyImpulseProbe] failed to request chunks", ex);
    ticksRemaining = 0; measuring = true;
   }
   return;
  }
  if (!bodiesCreated) {
   chunkWaitTicksElapsed++;
   if (!chunksActuallyLoaded(level)) {
    if (chunkWaitTicksElapsed > MAX_CHUNK_WAIT_TICKS) {
     LOGGER.error("[DIV twoBodyImpulseProbe] FAILED - chunks never finished loading within " + MAX_CHUNK_WAIT_TICKS + " ticks");
     ticksRemaining = 0; measuring = true;
    }
    return;
   }
   if (!isClearOfTerrain(level, halfScenario.centerPosition) || !isClearOfTerrain(level, quarterScenario.centerPosition)) {
    LOGGER.error("[DIV twoBodyImpulseProbe] FAILED - scene is NOT verified terrain-free at Y=" + SCENE_Y
      + " (found a non-air block within the scanned clearance box) - refusing to trust a terrain-contaminated run");
    ticksRemaining = 0; measuring = true;
    return;
   }
   try {
    createBodies(level);
    bodiesCreated = true;
   } catch (RuntimeException ex) {
    LOGGER.error("[DIV twoBodyImpulseProbe] failed to create bodies", ex);
    ticksRemaining = 0; measuring = true;
   }
   return;
  }
  if (!impulseApplied) {
   activationTicksElapsed++;
   if (halfScenario.bothHalvesReady() && quarterScenario.bothHalvesReady()) {
    applyImpulseAndCaptureBefore();
    impulseApplied = true;
    measuring = true;
    ticksRemaining = MEASURE_TICKS;
    LOGGER.info("[DIV twoBodyImpulseProbe] both scenarios activated after " + activationTicksElapsed + " ticks - impulse applied, beginning " + MEASURE_TICKS + "-tick measurement");
   } else if (activationTicksElapsed > MAX_ACTIVATION_TICKS) {
    LOGGER.error("[DIV twoBodyImpulseProbe] FAILED - halves never became ready within " + MAX_ACTIVATION_TICKS + " ticks (half=" + halfScenario.bothHalvesReady() + " quarter=" + quarterScenario.bothHalvesReady() + ")");
    ticksRemaining = 0; measuring = true;
   }
   return;
  }
  if (ticksRemaining <= 0) return;
  logTick(level, halfScenario);
  logTick(level, quarterScenario);
  ticksRemaining--;
  if (ticksRemaining == 0) {
   LOGGER.info("[DIV twoBodyImpulseProbe] COMPLETE - the tick+1 ISOLATED-vs-COMBINED-vs-MEASURED comparison "
     + "above answers review 53280; later ticks' pose/drift/MEASURED lines are settling/liveness evidence only.");
  }
 }

 private static void requestChunks(ServerLevel level) {
  Vector3d posHalf = new Vector3d(5000, SCENE_Y, 5000);
  // +512 (a multiple of 16, the chunk size) keeps the SAME within-chunk X/Z alignment as
  // posHalf, so isClearOfTerrain's radius-HORIZONTAL_CLEARANCE_RADIUS scan around either
  // position stays inside its own single force-loaded chunk - an unaligned offset (e.g. the
  // original +500) could spill the scan into a neighboring, never-requested/unloaded chunk.
  Vector3d posQuarter = new Vector3d(posHalf).add(512, 0, 0);
  halfScenario = new Scenario("f=0.5", TwoBodyChassis.Config.DEFAULT, posHalf);
  quarterScenario = new Scenario("f=0.4", new TwoBodyChassis.Config(0.4), posQuarter);
  int chunkX1 = Math.floorDiv((int) Math.floor(posHalf.x), 16), chunkZ1 = Math.floorDiv((int) Math.floor(posHalf.z), 16);
  int chunkX2 = Math.floorDiv((int) Math.floor(posQuarter.x), 16), chunkZ2 = Math.floorDiv((int) Math.floor(posQuarter.z), 16);
  chunkCoordsToAwait = new int[]{chunkX1, chunkZ1, chunkX2, chunkZ2};
  level.setChunkForced(chunkX1, chunkZ1, true);
  level.setChunkForced(chunkX2, chunkZ2, true);
  LOGGER.info("[DIV twoBodyImpulseProbe] requested chunks (" + chunkX1 + "," + chunkZ1 + ") and (" + chunkX2 + "," + chunkZ2 + ") at Y=" + SCENE_Y);
 }

 /** Review 53259's own lesson (AnchorFrameProbe) applies here too: {@code setChunkForced} only
  * SCHEDULES loading - confirm via {@code ServerChunkCache.getChunkNow} before creating a body
  * there. */
 private static boolean chunksActuallyLoaded(ServerLevel level) {
  var chunkSource = level.getChunkSource();
  return chunkSource.getChunkNow(chunkCoordsToAwait[0], chunkCoordsToAwait[1]) != null
    && chunkSource.getChunkNow(chunkCoordsToAwait[2], chunkCoordsToAwait[3]) != null;
 }

 /** Review 53282's explicit "verified clearance above terrain or disposable void scene" - rather
  * than assuming Y=250 is clear, actually scans every block in a box wide enough to cover the
  * chassis itself plus the whole {@link #MEASURE_TICKS} free-fall range (and a margin above, in
  * case the impulse's own vertical component pushes a half upward instead of down) and confirms
  * every one is air. Returns false (never throws) on any non-air block found, so the caller can
  * fail the probe cleanly instead of silently trusting a contaminated scene. */
 private static boolean isClearOfTerrain(ServerLevel level, Vector3d center) {
  int cx = (int) Math.floor(center.x), cy = (int) Math.floor(center.y), cz = (int) Math.floor(center.z);
  for (int dx = -HORIZONTAL_CLEARANCE_RADIUS; dx <= HORIZONTAL_CLEARANCE_RADIUS; dx++) {
   for (int dz = -HORIZONTAL_CLEARANCE_RADIUS; dz <= HORIZONTAL_CLEARANCE_RADIUS; dz++) {
    for (int dy = -DOWN_CLEARANCE; dy <= UP_CLEARANCE; dy++) {
     if (!level.getBlockState(new BlockPos(cx + dx, cy + dy, cz + dz)).isAir()) return false;
    }
   }
  }
  return true;
 }

 /** Builds both scenarios via the real production construction path
  * ({@code TwoBodyLifecycle.create}, the exact method {@code PhysicsBridge.Body}'s own
  * constructor calls when {@code EXPERIMENTAL_TWO_BODY_CHASSIS} is on) at IDENTITY orientation -
  * deliberately isolating this probe to the mass/inertia-coupling question, not the anchor-frame
  * convention question {@code AnchorFrameProbe} already settled. */
 private static void createBodies(ServerLevel level) {
  Vector3d halfExtents = new Vector3d(HALF_EXTENT_A, HALF_HEIGHT_B, HALF_EXTENT_C);
  halfScenario.bodies = TwoBodyLifecycle.create(level, halfScenario.centerPosition, new Quaterniond(), halfExtents, TOTAL_MASS_KG, halfScenario.config);
  quarterScenario.bodies = TwoBodyLifecycle.create(level, quarterScenario.centerPosition, new Quaterniond(), halfExtents, TOTAL_MASS_KG, quarterScenario.config);
  LOGGER.info("[DIV twoBodyImpulseProbe] scene verified clear of terrain; bodies created after " + chunkWaitTicksElapsed + " chunk-wait ticks - "
    + "half(lowerRuntimeId=" + halfScenario.bodies.lowerBox().getRuntimeId() + " upperRuntimeId=" + halfScenario.bodies.upperBox().getRuntimeId() + ") "
    + "quarter(lowerRuntimeId=" + quarterScenario.bodies.lowerBox().getRuntimeId() + " upperRuntimeId=" + quarterScenario.bodies.upperBox().getRuntimeId() + ")");
 }

 /** Captures each scenario's BEFORE velocities, then applies {@link #IMPULSE} to the LOWER half
  * only, at {@link #WHEEL_ANCHOR_FROM_G} expressed relative to the lower half's own center
  * ({@code WHEEL_ANCHOR_FROM_G - TwoBodyChassis.lowerCenterOffsetFromG(HALF_HEIGHT_B)}) - since
  * construction orientation is IDENTITY here, this world-frame offset IS the local-frame point
  * {@code applyImpulseAtPoint} needs directly, with no inverse-orientation transform required
  * (the same transform {@code PhysicsBridge.Body.stepGround} itself applies via {@code inverse}
  * before its own {@code applyImpulseAtPoint} call - skipped here only because identity makes it
  * a no-op, not because it would otherwise be unnecessary). */
 private static void applyImpulseAndCaptureBefore() {
  for (Scenario s : new Scenario[]{halfScenario, quarterScenario}) {
   s.lowerVelBefore = s.bodies.lowerHandle().getLinearVelocity(new Vector3d());
   s.upperVelBefore = s.bodies.upperHandle().getLinearVelocity(new Vector3d());
   s.lowerAngularBefore = s.bodies.lowerHandle().getAngularVelocity(new Vector3d());
   s.upperAngularBefore = s.bodies.upperHandle().getAngularVelocity(new Vector3d());
   Vector3d localPointOnLower = new Vector3d(WHEEL_ANCHOR_FROM_G).sub(TwoBodyChassis.lowerCenterOffsetFromG(HALF_HEIGHT_B));
   s.bodies.lowerHandle().applyImpulseAtPoint(localPointOnLower, new Vector3d(IMPULSE));
   LOGGER.info("[DIV twoBodyImpulseProbe] scenario=" + s.label + " impulse=" + IMPULSE + " appliedAtLocalPoint=" + localPointOnLower
     + " lowerMassKg=" + TwoBodyChassis.lowerMassKg(TOTAL_MASS_KG, s.config) + " upperMassKg=" + TwoBodyChassis.upperMassKg(TOTAL_MASS_KG, s.config)
     + " lowerVelBefore=" + s.lowerVelBefore + " upperVelBefore=" + s.upperVelBefore);
  }
 }

 /** World-space distance between the shared joint anchor point G as computed independently from
  * each half's own current pose/orientation - the direct, live "is the joint actually holding"
  * evidence review 53282 asked for (same invariant {@code AnchorFrameProbe} itself checks, here
  * simpler since both halves' local anchors represent the exact same point G, not two
  * independently-hypothesized offsets). */
 private static double jointDrift(Scenario s) {
  var lowerPose = s.bodies.lowerBox().getPose();
  var upperPose = s.bodies.upperBox().getPose();
  Vector3d gFromLower = new Vector3d(lowerPose.orientation().transform(TwoBodyChassis.jointAnchorLocalInLowerBody(HALF_HEIGHT_B))).add(lowerPose.position());
  Vector3d gFromUpper = new Vector3d(upperPose.orientation().transform(TwoBodyChassis.jointAnchorLocalInUpperBody(HALF_HEIGHT_B))).add(upperPose.position());
  return gFromLower.distance(gFromUpper);
 }

 private static void logTick(ServerLevel level, Scenario s) {
  s.ticksSinceImpulse++;
  s.bodies.lowerBox().updatePose();
  s.bodies.upperBox().updatePose();
  var lowerPose = s.bodies.lowerBox().getPose();
  var upperPose = s.bodies.upperBox().getPose();
  double drift = jointDrift(s);

  Vector3d gravity = DimensionPhysicsData.getGravity(level);
  Vector3d expectedGravityDeltaV = new Vector3d(gravity).mul(0.05 * s.ticksSinceImpulse); // DT=0.05, same constant PhysicsBridge.DT uses

  Vector3d lowerVelNow = s.bodies.lowerHandle().getLinearVelocity(new Vector3d());
  Vector3d upperVelNow = s.bodies.upperHandle().getLinearVelocity(new Vector3d());
  Vector3d lowerAngularNow = s.bodies.lowerHandle().getAngularVelocity(new Vector3d());
  Vector3d upperAngularNow = s.bodies.upperHandle().getAngularVelocity(new Vector3d());
  Vector3d measuredLowerDeltaV = new Vector3d(lowerVelNow).sub(s.lowerVelBefore).sub(expectedGravityDeltaV);
  Vector3d measuredUpperDeltaV = new Vector3d(upperVelNow).sub(s.upperVelBefore).sub(expectedGravityDeltaV);
  Vector3d measuredLowerDeltaAngular = new Vector3d(lowerAngularNow).sub(s.lowerAngularBefore);
  Vector3d measuredUpperDeltaAngular = new Vector3d(upperAngularNow).sub(s.upperAngularBefore);

  LOGGER.info("[DIV twoBodyImpulseProbe] scenario=" + s.label + " tick+" + s.ticksSinceImpulse
    + " MEASURED lowerDeltaV=" + measuredLowerDeltaV + " upperDeltaV=" + measuredUpperDeltaV
    + " lowerDeltaAngular=" + measuredLowerDeltaAngular + " upperDeltaAngular=" + measuredUpperDeltaAngular
    + " || lowerPos=" + lowerPose.position() + " lowerOrientation=" + lowerPose.orientation()
    + " upperPos=" + upperPose.position() + " upperOrientation=" + upperPose.orientation()
    + " jointDrift=" + drift + "m");

  // Review 53282: the ISOLATED/COMBINED comparison is only valid against the static
  // construction-time local offsets at tick+1 (negligible rotation accumulated in one 0.05s
  // step) - logged separately, once, rather than repeated every tick against an increasingly
  // stale approximation.
  if (s.ticksSinceImpulse == 1) logInitialResponseComparison(s);
 }

 /** Logged exactly once, at tick+1 - the ISOLATED prediction (current production friction-solver
  * assumption: impulse only ever acts on the owner half's own isolated mass/inertia, upper
  * entirely unaffected) versus the COMBINED prediction (physically-correct rigid-joint limit: one
  * shared deltaAngular from the aggregate inertia about the assembly COM, plus one
  * momentum-conserving COM-level deltaV, translated to each half's own center via
  * {@code v_body = v_com + omega x (bodyCenter - com)}) versus what {@link #logTick} already
  * measured this same tick. Valid here specifically because rotation accumulated in a single
  * 0.05s step since the impulse is small enough that the construction-time (identity-orientation)
  * local center offsets are still an accurate stand-in for the real current ones. */
 private static void logInitialResponseComparison(Scenario s) {
  double lowerMassKg = TwoBodyChassis.lowerMassKg(TOTAL_MASS_KG, s.config);
  Vector3d lowerOwnInertia = TwoBodyChassis.lowerOwnInertia(TOTAL_MASS_KG, HALF_EXTENT_A, HALF_HEIGHT_B, HALF_EXTENT_C, s.config);
  Vector3d localPointOnLower = new Vector3d(WHEEL_ANCHOR_FROM_G).sub(TwoBodyChassis.lowerCenterOffsetFromG(HALF_HEIGHT_B));
  Vector3d isolatedDeltaV = new Vector3d(IMPULSE).div(lowerMassKg);
  Vector3d isolatedTorque = new Vector3d(localPointOnLower).cross(IMPULSE);
  Vector3d isolatedDeltaAngular = new Vector3d(isolatedTorque.x / lowerOwnInertia.x, isolatedTorque.y / lowerOwnInertia.y, isolatedTorque.z / lowerOwnInertia.z);

  Vector3d aggregateInertia = TwoBodyChassis.aggregateInertiaAboutAssemblyCom(TOTAL_MASS_KG, HALF_EXTENT_A, HALF_HEIGHT_B, HALF_EXTENT_C, s.config);
  Vector3d assemblyComFromG = new Vector3d(0, TwoBodyChassis.assemblyComOffsetFromG(HALF_HEIGHT_B, s.config), 0);
  Vector3d localPointFromAssemblyCom = new Vector3d(WHEEL_ANCHOR_FROM_G).sub(assemblyComFromG);
  Vector3d combinedDeltaVCom = new Vector3d(IMPULSE).div(TOTAL_MASS_KG);
  Vector3d combinedTorque = new Vector3d(localPointFromAssemblyCom).cross(IMPULSE);
  Vector3d combinedDeltaAngular = new Vector3d(combinedTorque.x / aggregateInertia.x, combinedTorque.y / aggregateInertia.y, combinedTorque.z / aggregateInertia.z);
  Vector3d lowerCenterFromG = TwoBodyChassis.lowerCenterOffsetFromG(HALF_HEIGHT_B), upperCenterFromG = TwoBodyChassis.upperCenterOffsetFromG(HALF_HEIGHT_B);
  Vector3d combinedLowerDeltaV = new Vector3d(combinedDeltaVCom).add(new Vector3d(combinedDeltaAngular).cross(new Vector3d(lowerCenterFromG).sub(assemblyComFromG)));
  Vector3d combinedUpperDeltaV = new Vector3d(combinedDeltaVCom).add(new Vector3d(combinedDeltaAngular).cross(new Vector3d(upperCenterFromG).sub(assemblyComFromG)));

  LOGGER.info("[DIV twoBodyImpulseProbe] scenario=" + s.label + " tick+1 INITIAL-RESPONSE-COMPARISON"
    + " || ISOLATED lowerDeltaV=" + isolatedDeltaV + " lowerDeltaAngular=" + isolatedDeltaAngular + " (upper predicted unaffected)"
    + " || COMBINED lowerDeltaV=" + combinedLowerDeltaV + " upperDeltaV=" + combinedUpperDeltaV + " sharedDeltaAngular=" + combinedDeltaAngular
    + " || compare against this scenario's tick+1 MEASURED line above to answer review 53280");
 }
}
