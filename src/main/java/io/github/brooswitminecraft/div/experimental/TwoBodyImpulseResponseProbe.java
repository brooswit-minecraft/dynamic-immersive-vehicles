package io.github.brooswitminecraft.div.experimental;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import com.mojang.logging.LogUtils;
import io.github.brooswitminecraft.div.TwoBodyLifecycle;
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
 * If the isolated-half prediction and the real combined-system response disagree materially, the
 * production friction solver's own impulse-sizing math (not merely its target-point bookkeeping,
 * which {@code TwoBodyChassisGroundForceCompositionTest} already covers) is working from the
 * wrong physical model.
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
 * reliable), apply ONE known impulse directly to the LOWER half (the realistic case per
 * {@code TwoBodyChassis.isUpperHalf}'s own doc - every wheel on a typical ground vehicle sits
 * below the chassis center) at a representative wheel-anchor point, then let Sable's own engine
 * step the next {@link #MEASURE_TICKS} real server ticks naturally (no manual stepping - same as
 * {@code AnchorFrameProbe}, Sable already ticks every server tick on its own) while logging both
 * halves' real velocities each tick.
 *
 * <p>Three numbers are compared for both halves at every logged tick, gravity-compensated using
 * this level's own real {@code DimensionPhysicsData.getGravity} (not a hardcoded constant, so
 * this stays correct even if that value is ever tuned):
 * <ul>
 *  <li>MEASURED - the real velocity change Sable actually produced.</li>
 *  <li>ISOLATED prediction - what the current production friction solver's own math implicitly
 *  assumes: {@code deltaV=impulse/lowerMassKg}, {@code deltaAngular} from
 *  {@code lowerOwnInertia} alone, with the UPPER half entirely unaffected this tick.</li>
 *  <li>COMBINED prediction - the physically-correct infinitely-rigid-joint limit: both halves
 *  share one {@code deltaAngular} (computed from
 *  {@code TwoBodyChassis.aggregateInertiaAboutAssemblyCom} about the assembly COM) plus one
 *  COM-level {@code deltaV=impulse/totalMassKg} (true by conservation of momentum regardless of
 *  internal joint stiffness), translated out to each half's own center via the rigid-body
 *  point-velocity formula.</li>
 * </ul>
 * Whichever prediction the measured response actually tracks is this ticket's answer to 53280;
 * if it is COMBINED (the physically expected outcome for a 6-axis-locked fixed joint - Rapier's
 * own constraint solver resolves the joint simultaneously with the applied impulse, not as a
 * later correction), that is direct evidence the friction solver's impulse-sizing math should be
 * revisited to use the aggregate mass/inertia rather than the owner half's own isolated values -
 * a follow-up fix, not made speculatively here, pending this probe's own real CI evidence (per
 * this project's standing "never ship a physics fix without validating against real data" rule).
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
 private static final Vector3d IMPULSE = new Vector3d(60, 900, 150); // N*s - vertical-dominant (suspension support) plus longitudinal/lateral (drive+turn), matching a real per-tick wheel contact impulse's shape

 private static boolean chunksRequested = false, bodiesCreated = false, measuring = false;
 private static int chunkWaitTicksElapsed = 0, activationTicksElapsed = 0, ticksRemaining = 0;
 private static boolean impulseApplied = false;
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
  int tickOfImpulse = -1;
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
    applyImpulseAndCaptureBefore(level);
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
   LOGGER.info("[DIV twoBodyImpulseProbe] COMPLETE - for each scenario, compare MEASURED deltaV/deltaAngular "
     + "against ISOLATED (current production impulse-sizing assumption) and COMBINED (physically-correct "
     + "rigid-joint prediction) above. Whichever prediction MEASURED actually tracks answers review 53280.");
  }
 }

 private static void requestChunks(ServerLevel level) {
  Vector3d posHalf = new Vector3d(5000, 80, 5000);
  Vector3d posQuarter = new Vector3d(posHalf).add(500, 0, 0);
  halfScenario = new Scenario("f=0.5", TwoBodyChassis.Config.DEFAULT, posHalf);
  quarterScenario = new Scenario("f=0.4", new TwoBodyChassis.Config(0.4), posQuarter);
  int chunkX1 = Math.floorDiv((int) Math.floor(posHalf.x), 16), chunkZ1 = Math.floorDiv((int) Math.floor(posHalf.z), 16);
  int chunkX2 = Math.floorDiv((int) Math.floor(posQuarter.x), 16), chunkZ2 = Math.floorDiv((int) Math.floor(posQuarter.z), 16);
  chunkCoordsToAwait = new int[]{chunkX1, chunkZ1, chunkX2, chunkZ2};
  level.setChunkForced(chunkX1, chunkZ1, true);
  level.setChunkForced(chunkX2, chunkZ2, true);
  LOGGER.info("[DIV twoBodyImpulseProbe] requested chunks (" + chunkX1 + "," + chunkZ1 + ") and (" + chunkX2 + "," + chunkZ2 + ")");
 }

 /** Review 53259's own lesson (AnchorFrameProbe) applies here too: {@code setChunkForced} only
  * SCHEDULES loading - confirm via {@code ServerChunkCache.getChunkNow} before creating a body
  * there. */
 private static boolean chunksActuallyLoaded(ServerLevel level) {
  var chunkSource = level.getChunkSource();
  return chunkSource.getChunkNow(chunkCoordsToAwait[0], chunkCoordsToAwait[1]) != null
    && chunkSource.getChunkNow(chunkCoordsToAwait[2], chunkCoordsToAwait[3]) != null;
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
  LOGGER.info("[DIV twoBodyImpulseProbe] bodies created after " + chunkWaitTicksElapsed + " chunk-wait ticks - "
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
 private static void applyImpulseAndCaptureBefore(ServerLevel level) {
  for (Scenario s : new Scenario[]{halfScenario, quarterScenario}) {
   s.lowerVelBefore = s.bodies.lowerHandle().getLinearVelocity(new Vector3d());
   s.upperVelBefore = s.bodies.upperHandle().getLinearVelocity(new Vector3d());
   s.lowerAngularBefore = s.bodies.lowerHandle().getAngularVelocity(new Vector3d());
   s.upperAngularBefore = s.bodies.upperHandle().getAngularVelocity(new Vector3d());
   Vector3d localPointOnLower = new Vector3d(WHEEL_ANCHOR_FROM_G).sub(TwoBodyChassis.lowerCenterOffsetFromG(HALF_HEIGHT_B));
   s.bodies.lowerHandle().applyImpulseAtPoint(localPointOnLower, new Vector3d(IMPULSE));
   s.tickOfImpulse = activationTicksElapsed;
   LOGGER.info("[DIV twoBodyImpulseProbe] scenario=" + s.label + " impulse=" + IMPULSE + " appliedAtLocalPoint=" + localPointOnLower
     + " lowerMassKg=" + TwoBodyChassis.lowerMassKg(TOTAL_MASS_KG, s.config) + " upperMassKg=" + TwoBodyChassis.upperMassKg(TOTAL_MASS_KG, s.config)
     + " lowerVelBefore=" + s.lowerVelBefore + " upperVelBefore=" + s.upperVelBefore);
  }
 }

 private static void logTick(ServerLevel level, Scenario s) {
  int ticksSinceImpulse = activationTicksElapsed - s.tickOfImpulse + (MEASURE_TICKS - ticksRemaining);
  Vector3d gravity = DimensionPhysicsData.getGravity(level);
  Vector3d expectedGravityDeltaV = new Vector3d(gravity).mul(0.05 * ticksSinceImpulse); // DT=0.05, same constant PhysicsBridge.DT uses

  Vector3d lowerVelNow = s.bodies.lowerHandle().getLinearVelocity(new Vector3d());
  Vector3d upperVelNow = s.bodies.upperHandle().getLinearVelocity(new Vector3d());
  Vector3d lowerAngularNow = s.bodies.lowerHandle().getAngularVelocity(new Vector3d());
  Vector3d upperAngularNow = s.bodies.upperHandle().getAngularVelocity(new Vector3d());
  Vector3d measuredLowerDeltaV = new Vector3d(lowerVelNow).sub(s.lowerVelBefore).sub(expectedGravityDeltaV);
  Vector3d measuredUpperDeltaV = new Vector3d(upperVelNow).sub(s.upperVelBefore).sub(expectedGravityDeltaV);
  Vector3d measuredLowerDeltaAngular = new Vector3d(lowerAngularNow).sub(s.lowerAngularBefore);
  Vector3d measuredUpperDeltaAngular = new Vector3d(upperAngularNow).sub(s.upperAngularBefore);

  // ISOLATED prediction (current production friction-solver assumption): the impulse only ever
  // acts on the owner half's own mass/inertia, as if it were a free body - upper entirely
  // unaffected.
  double lowerMassKg = TwoBodyChassis.lowerMassKg(TOTAL_MASS_KG, s.config);
  Vector3d lowerOwnInertia = TwoBodyChassis.lowerOwnInertia(TOTAL_MASS_KG, HALF_EXTENT_A, HALF_HEIGHT_B, HALF_EXTENT_C, s.config);
  Vector3d localPointOnLower = new Vector3d(WHEEL_ANCHOR_FROM_G).sub(TwoBodyChassis.lowerCenterOffsetFromG(HALF_HEIGHT_B));
  Vector3d isolatedDeltaV = new Vector3d(IMPULSE).div(lowerMassKg);
  Vector3d isolatedTorque = new Vector3d(localPointOnLower).cross(IMPULSE);
  Vector3d isolatedDeltaAngular = new Vector3d(isolatedTorque.x / lowerOwnInertia.x, isolatedTorque.y / lowerOwnInertia.y, isolatedTorque.z / lowerOwnInertia.z);

  // COMBINED prediction (physically-correct rigid-joint limit): one shared angular velocity
  // change for the whole weldment (aggregate inertia about the assembly COM), plus one COM-level
  // linear velocity change (true by conservation of momentum regardless of joint stiffness),
  // translated to each half's own center via v_body = v_com + omega x (bodyCenter - com).
  Vector3d aggregateInertia = TwoBodyChassis.aggregateInertiaAboutAssemblyCom(TOTAL_MASS_KG, HALF_EXTENT_A, HALF_HEIGHT_B, HALF_EXTENT_C, s.config);
  Vector3d assemblyComFromG = new Vector3d(0, TwoBodyChassis.assemblyComOffsetFromG(HALF_HEIGHT_B, s.config), 0);
  Vector3d localPointFromAssemblyCom = new Vector3d(WHEEL_ANCHOR_FROM_G).sub(assemblyComFromG);
  Vector3d combinedDeltaVCom = new Vector3d(IMPULSE).div(TOTAL_MASS_KG);
  Vector3d combinedTorque = new Vector3d(localPointFromAssemblyCom).cross(IMPULSE);
  Vector3d combinedDeltaAngular = new Vector3d(combinedTorque.x / aggregateInertia.x, combinedTorque.y / aggregateInertia.y, combinedTorque.z / aggregateInertia.z);
  Vector3d lowerCenterFromG = TwoBodyChassis.lowerCenterOffsetFromG(HALF_HEIGHT_B), upperCenterFromG = TwoBodyChassis.upperCenterOffsetFromG(HALF_HEIGHT_B);
  Vector3d combinedLowerDeltaV = new Vector3d(combinedDeltaVCom).add(new Vector3d(combinedDeltaAngular).cross(new Vector3d(lowerCenterFromG).sub(assemblyComFromG)));
  Vector3d combinedUpperDeltaV = new Vector3d(combinedDeltaVCom).add(new Vector3d(combinedDeltaAngular).cross(new Vector3d(upperCenterFromG).sub(assemblyComFromG)));

  LOGGER.info("[DIV twoBodyImpulseProbe] scenario=" + s.label + " tick+" + ticksSinceImpulse
    + " MEASURED lowerDeltaV=" + measuredLowerDeltaV + " upperDeltaV=" + measuredUpperDeltaV
    + " lowerDeltaAngular=" + measuredLowerDeltaAngular + " upperDeltaAngular=" + measuredUpperDeltaAngular
    + " || ISOLATED lowerDeltaV=" + isolatedDeltaV + " lowerDeltaAngular=" + isolatedDeltaAngular + " (upper predicted unaffected)"
    + " || COMBINED lowerDeltaV=" + combinedLowerDeltaV + " upperDeltaV=" + combinedUpperDeltaV + " sharedDeltaAngular=" + combinedDeltaAngular);
 }
}
